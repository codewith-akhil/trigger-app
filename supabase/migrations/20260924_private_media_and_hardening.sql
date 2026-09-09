-- ============================================================================
-- 20260924_private_media_and_hardening.sql
--
-- Task 24 — five priority fixes, DB half:
--   §1  chat_media + voice_notes buckets → PRIVATE (undo 20260918/20260922)
--   §2  Storage RLS: drop any-authenticated read; add participant-only read
--       (the two conversation participants; object folder = uploader uuid)
--   §3  messages.media_url / media_thumbnail: rewrite permanent /object/public/
--       URLs to bare object paths (private buckets ⇒ no public URLs anywhere;
--       clients mint short-lived signed URLs on demand via bucket+path)
--   §4  try_send_pending_message: verify the sender is actually a participant
--   §5  message_reactions RLS: INSERT/DELETE restricted to conversation
--       participants (UNIQUE(message_id,user_id) already exists)
--   §6  messages RLS: DELETE tightened to the message sender only (a
--       conversation owner could previously hard-delete the peer's rows)
--   §7  get_or_create_conversation RPC: advisory-lock-guarded, race-free
--       find-or-create used by send-message / send-message-request so two
--       simultaneous first-sends can never yield duplicate pair rows
--   §8  one-time safety dedupe of duplicate PENDING pair rows (oldest wins;
--       only message-less pending losers are removed)
--
-- Idempotent: every statement is guarded (IF EXISTS / WHERE … / DO blocks).
-- Apply via Management API SQL endpoint, supabase db push, or psql.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- §1  Buckets → private
-- ---------------------------------------------------------------------------
update storage.buckets
   set public = false
 where name in ('chat_media', 'voice_notes')
   and public is distinct from false;

-- ---------------------------------------------------------------------------
-- §2  Storage RLS
-- ---------------------------------------------------------------------------
drop policy if exists "chat_media_authenticated_read" on storage.objects;
drop policy if exists "voice_notes_authenticated_read" on storage.objects;
-- (any other wide-open read policy variants across history)
drop policy if exists "chat_media_public_read" on storage.objects;
drop policy if exists "voice_notes_public_read" on storage.objects;

-- Participant read for chat_media: object paths are "{uploader-uid}/{uuid}.ext",
-- so a viewer may read an object when they own it, or when a conversation row
-- (either mirror direction, pending or accepted) links the viewer to the
-- folder's uploader. Declined/blocked threads stop granting NEW signatures.
drop policy if exists "chat_media_participant_read" on storage.objects;
create policy "chat_media_participant_read"
  on storage.objects for select to authenticated
  using (
    bucket_id = 'chat_media'
    and (
      owner = auth.uid()
      or exists (
        select 1
          from public.conversations c
         where c.request_status in ('pending', 'accepted')
           and (
             (c.owner_id = auth.uid() and c.peer_id::text = (storage.foldername(name))[1])
             or
             (c.peer_id = auth.uid() and c.owner_id::text = (storage.foldername(name))[1])
           )
      )
    )
  );

drop policy if exists "voice_notes_participant_read" on storage.objects;
create policy "voice_notes_participant_read"
  on storage.objects for select to authenticated
  using (
    bucket_id = 'voice_notes'
    and (
      owner = auth.uid()
      or exists (
        select 1
          from public.conversations c
         where c.request_status in ('pending', 'accepted')
           and (
             (c.owner_id = auth.uid() and c.peer_id::text = (storage.foldername(name))[1])
             or
             (c.peer_id = auth.uid() and c.owner_id::text = (storage.foldername(name))[1])
           )
      )
    )
  );

-- Vault media stays strictly owner-only (re-assert; no-op when unchanged).
-- Existing vault_media_* policies already enforce owner = auth.uid().

-- ---------------------------------------------------------------------------
-- §3  Rewrite messages.media_url / media_thumbnail public URLs → bare paths
--     Idempotent: rows already carrying a bare path no longer match LIKE.
-- ---------------------------------------------------------------------------
-- 3a. Backfill media_bucket from the URL where it is missing (legacy rows).
update public.messages
   set media_bucket = (regexp_match(
         media_url,
         '/storage/v1/object/(?:public|sign|authenticated)/([^/]+)/'
       ))[1]
 where media_bucket is null
   and media_url like '%/storage/v1/object/%';

-- 3b. Same backfill for thumbnails (only when the parent row has no bucket
--     yet — thumbnail always lives in the same bucket as the parent).
update public.messages
   set media_bucket = (regexp_match(
         coalesce(nullif(media_thumbnail, ''), media_url),
         '/storage/v1/object/(?:public|sign|authenticated)/([^/]+)/'
       ))[1]
 where media_bucket is null
   and (coalesce(nullif(media_thumbnail, ''), media_url) like '%/storage/v1/object/%');

-- 3c. media_url → bare object path ("{uid}/{uuid}.ext"). Only /object/public/
--     /sign/ /authenticated/ forms exist historically (20260919 normalized all
--     signed URLs to public form; uploads bake public URLs since then).
update public.messages
   set media_url = substring(
         media_url from '/storage/v1/object/(?:public|sign|authenticated)/[^/]+/(.*)$'
       )
 where media_url like '%/storage/v1/object/%';

-- 3d. media_thumbnail → bare object path.
update public.messages
   set media_thumbnail = substring(
         media_thumbnail from '/storage/v1/object/(?:public|sign|authenticated)/[^/]+/(.*)$'
       )
 where media_thumbnail like '%/storage/v1/object/%';

-- ---------------------------------------------------------------------------
-- §4  try_send_pending_message — participant verification added (defense in
--     depth: it is granted to service_role only, but every service-role caller
--     passes client-supplied ids, so the RPC itself must verify membership).
--     Atomicity is unchanged: pg_advisory_xact_lock + SELECT … FOR UPDATE +
--     pending 3-message cap inside the lock.
-- ---------------------------------------------------------------------------
create or replace function public.try_send_pending_message(
  p_conversation_id uuid,
  p_sender_id uuid,
  p_text text,
  p_timestamp_millis bigint default null
)
returns public.messages
language plpgsql
security definer
set search_path to 'public'
as $function$
declare
  v_status text;
  v_owner  uuid;
  v_peer   uuid;
  v_msg    public.messages;
begin
  perform pg_advisory_xact_lock(hashtextextended(p_conversation_id::text, 0));

  select request_status, owner_id, peer_id
    into v_status, v_owner, v_peer
    from public.conversations
   where id = p_conversation_id
   for update;

  if not found then
    raise exception 'CONVERSATION_NOT_FOUND';
  end if;

  -- Sender must be one of the two conversation participants.
  if v_owner is distinct from p_sender_id and v_peer is distinct from p_sender_id then
    raise exception 'NOT_A_PARTICIPANT';
  end if;

  if v_status = 'blocked' then
    raise exception 'CONVERSATION_BLOCKED';
  end if;
  if v_status = 'declined' then
    raise exception 'REQUEST_DECLINED';
  end if;
  if v_status = 'pending' then
    if v_owner <> p_sender_id then
      raise exception 'REQUEST_NOT_ACCEPTED';
    end if;
    if (
      select count(*)
        from public.messages
       where conversation_id = p_conversation_id
         and sender_id = p_sender_id
    ) >= 3 then
      raise exception 'REQUEST_MESSAGE_LIMIT';
    end if;
  end if;

  insert into public.messages
    (conversation_id, sender_id, type, text, status, timestamp_millis, is_outgoing)
  values (
    p_conversation_id, p_sender_id, 'TEXT', left(coalesce(p_text, ''), 10000),
    'SENT',
    coalesce(p_timestamp_millis, (extract(epoch from now()) * 1000)::bigint),
    true
  )
  returning * into v_msg;

  return v_msg;
end;
$function$;

revoke all on function public.try_send_pending_message(uuid, uuid, text, bigint) from public, anon, authenticated;
grant execute on function public.try_send_pending_message(uuid, uuid, text, bigint) to service_role;

-- ---------------------------------------------------------------------------
-- §5  message_reactions — participant-gated INSERT/DELETE.
--     UNIQUE(message_id, user_id) already exists
--     (message_reactions_message_id_user_id_key) so one reaction per user per
--     message is enforced at the DB level; same-emoji-remove / different-emoji
--     switch lives in the toggle-reaction edge function on top of it.
-- ---------------------------------------------------------------------------
drop policy if exists "mr_self_insert" on public.message_reactions;
drop policy if exists "mr_participant_insert" on public.message_reactions;
create policy "mr_participant_insert"
  on public.message_reactions for insert to authenticated
  with check (
    user_id = auth.uid()
    and exists (
      select 1
        from public.messages m
        join public.conversations c on c.id = m.conversation_id
       where m.id = message_reactions.message_id
         and (c.owner_id = auth.uid() or c.peer_id = auth.uid())
    )
  );

drop policy if exists "mr_self_delete" on public.message_reactions;
drop policy if exists "mr_participant_delete" on public.message_reactions;
create policy "mr_participant_delete"
  on public.message_reactions for delete to authenticated
  using (
    user_id = auth.uid()
    and exists (
      select 1
        from public.messages m
        join public.conversations c on c.id = m.conversation_id
       where m.id = message_reactions.message_id
         and (c.owner_id = auth.uid() or c.peer_id = auth.uid())
    )
  );

-- ---------------------------------------------------------------------------
-- §6  messages DELETE → sender only. The old policy let the conversation OWNER
--     hard-delete the PEER's message rows for both sides (single canonical row
--     per thread). Delete-for-me is a Room-local operation; delete-for-everyone
--     goes through the delete-message edge function which already verifies the
--     caller is the sender.
-- ---------------------------------------------------------------------------
drop policy if exists "msg_participant_delete" on public.messages;
create policy "msg_participant_delete"
  on public.messages for delete to authenticated
  using (sender_id = auth.uid());

-- ---------------------------------------------------------------------------
-- §7  get_or_create_conversation — atomic find-or-create guarded by an
--     advisory lock keyed on the UNORDERED pair, so two simultaneous
--     first-sends from opposite sides serialize and only ONE pending row is
--     created (the ordered-pair unique index cannot span (A,B)+(B,A)).
-- ---------------------------------------------------------------------------
create or replace function public.get_or_create_conversation(
  p_owner uuid,
  p_peer uuid,
  p_peer_name text default 'Unknown'
)
returns table (conversation_id uuid, was_created boolean)
language plpgsql
security definer
set search_path to 'public'
as $function$
declare
  v_existing public.conversations%rowtype;
  v_new      public.conversations%rowtype;
  v_sender   public.profiles%rowtype;
begin
  -- Lock on the unordered pair: (A,B) and (B,A) contend on the same key.
  perform pg_advisory_xact_lock(hashtextextended(
    least(p_owner::text, p_peer::text) || ':' || greatest(p_owner::text, p_peer::text), 42));

  select * into v_existing
    from public.conversations
   where (owner_id = p_owner and peer_id = p_peer)
      or (owner_id = p_peer and peer_id = p_owner)
   order by created_at asc, id asc
   limit 1;

  if found then
    return query select v_existing.id, false;
    return;
  end if;

  -- First contact: create ONE pending sender-owned row (the canonical row).
  select * into v_sender from public.profiles where id = p_owner;

  insert into public.conversations (owner_id, peer_id, peer_name, request_status, is_group)
  values (p_owner, p_peer, coalesce(nullif(p_peer_name, ''), 'Unknown'), 'pending', false)
  returning * into v_new;

  -- Mirror the request so the receiver's Requests list shows it (non-fatal).
  begin
    insert into public.message_requests
      (sender_id, receiver_id, sender_name, sender_username, sender_avatar_url,
       initial_message, status, conversation_id)
    values
      (p_owner, p_peer,
       coalesce(nullif(v_sender.full_name, ''), p_peer_name, 'Unknown'),
       v_sender.username, v_sender.avatar_url,
       '', 'pending', v_new.id)
    on conflict (sender_id, receiver_id) do nothing;
  exception when others then
    null;  -- message_requests mirror is best-effort; the conversation row wins
  end;

  return query select v_new.id, true;
end;
$function$;

revoke all on function public.get_or_create_conversation(uuid, uuid, text) from public, anon, authenticated;
grant execute on function public.get_or_create_conversation(uuid, uuid, text) to service_role;

-- ---------------------------------------------------------------------------
-- §8  Safety dedupe: for any unordered pair holding MORE than one row, delete
--     the non-canonical rows ONLY when they are message-less PENDING rows
--     (the both-sides-first-send race loser). Accepted mirrors and rows with
--     messages are never touched.
-- ---------------------------------------------------------------------------
delete from public.conversations c
using public.conversations keeper
where c.peer_id is not null
  and keeper.peer_id is not null
  and keeper.id <> c.id
  and least(c.owner_id, c.peer_id) = least(keeper.owner_id, keeper.peer_id)
  and greatest(c.owner_id, c.peer_id) = greatest(keeper.owner_id, keeper.peer_id)
  -- canonical = oldest (created_at asc, id asc tie-break)
  and (keeper.created_at < c.created_at
       or (keeper.created_at = c.created_at and keeper.id < c.id))
  and c.request_status = 'pending'
  and not exists (select 1 from public.messages m where m.conversation_id = c.id);

-- ---------------------------------------------------------------------------
-- §9  Fix sync_message_reactions: when the LAST reaction of a message is
--     removed the trigger wrote messages.reactions = NULL, violating the
--     column's NOT NULL constraint (23502) and silently breaking every
--     same-emoji-removes-the-last-reaction toggle (the edge fn ignored the
--     delete error). Store an empty object instead.
-- ---------------------------------------------------------------------------
create or replace function public.sync_message_reactions()
returns trigger
language plpgsql
security definer
set search_path to 'public'
as $function$
declare
  v_msg uuid := coalesce(new.message_id, old.message_id);
begin
  update public.messages m
     set reactions = agg.r
    from (
      -- coalesce: jsonb_object_agg over an EMPTY set yields NULL — assigning
      -- it to the NOT NULL messages.reactions column was the actual 23502
      -- that broke deleting a message's LAST reaction.
      coalesce(jsonb_object_agg(
               emoji,
               jsonb_build_object('count', cnt, 'users', users)
             ), '{}'::jsonb) as r
        from (
          select emoji,
                 count(*) as cnt,
                 jsonb_agg(user_id) as users
            from public.message_reactions
           where message_id = v_msg
           group by emoji
        ) t
    ) agg
   where m.id = v_msg;

  if not exists (select 1 from public.message_reactions where message_id = v_msg) then
    update public.messages set reactions = '{}'::jsonb where id = v_msg;
  end if;
  return null;
end;
$function$;

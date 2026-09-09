-- ============================================================================
-- 20260928_final_hardening.sql
-- Final audit-fix wave (post-Task-26 security audit findings):
--   F1  otp_codes: allow purpose 'account_delete' — delete-account-otp was
--       broken by the CHECK constraint (every OTP insert failed 23514).
--   F2  conversations: BEFORE UPDATE trigger — request_status / peer_id /
--       disappearing_* / owner_id may only be changed by the service role
--       (edge functions perform their own participant authorization first).
--       Previously the owner could self-accept a pending request via direct
--       PostgREST and bypass the 3-message budget entirely.
--   F3  Block enforcement at RLS level: messages INSERT and conversations
--       INSERT refuse rows for blocked pairs (either direction);
--       try_send_pending_message re-created with the same guard.
--   F4  profiles: is_online / last_seen_at removed from the anon/
--       authenticated column grant — presence is only served by
--       get-peer-presence (privacy four-level gate).
--   F5  Disappearing-message cleanup moves to a cron-driven edge function
--       that ALSO purges the storage objects (the plpgsql cron only deleted
--       DB rows, leaving playable media in chat_media/voice_notes forever).
--   F7  process_withdrawal: explicit EXECUTE for service_role
--       (wallet-withdraw is its only caller).
--   F8  Storage policies for documents / vault_media / backups /
--       stream_thumbnails accept both owner columns (owner OR owner_id) —
--       newer platform rows populate owner_id only (see 20260917 §note);
--       plus a folder-scoped DELETE policy for chat_media (the 20260904
--       owner-column-only policy 403s on newer rows).
--   F9  follows: SELECT scoped to your own social edges (graph dump closed).
--   F10 live_stream_comments: author-only DELETE; INSERT must present the
--       caller's own username (no spoofed display names).
--   F11 forward-message budget becomes advisory-lock atomic via RPC.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- F1  otp_codes purpose CHECK — add 'account_delete'
-- ---------------------------------------------------------------------------
alter table public.otp_codes drop constraint if exists otp_codes_purpose_check;
alter table public.otp_codes add constraint otp_codes_purpose_check
  check (purpose in (
    'signup','recovery','magic_link','email_change','phone_verify',
    'vault_reset','account_delete'
  ));

-- ---------------------------------------------------------------------------
-- F2  conversations protected columns
-- ---------------------------------------------------------------------------
create or replace function public.protect_conversation_columns()
returns trigger
language plpgsql
as $fn$
declare
  v_claim_role text;
  v_db_role    text;
begin
  v_claim_role := current_setting('request.jwt.claims', true)::json ->> 'role';
  v_db_role    := current_setting('role', true);
  -- service_role (edge functions after their own authz), superuser roles and
  -- the migration owner may change everything; everyone else is restricted.
  if coalesce(v_claim_role, '') = 'service_role'
     or coalesce(v_db_role, '') in ('service_role', 'postgres', 'supabase_admin') then
    return new;
  end if;
  if new.request_status        is distinct from old.request_status
     or new.peer_id            is distinct from old.peer_id
     or new.owner_id           is distinct from old.owner_id
     or new.disappearing_duration     is distinct from old.disappearing_duration
     or new.disappearing_updated_at   is distinct from old.disappearing_updated_at then
    raise exception 'PROTECTED_COLUMNS: request_status/peer_id/disappearing_* are server-managed';
  end if;
  return new;
end;
$fn$;

drop trigger if exists conversations_protected_columns on public.conversations;
create trigger conversations_protected_columns
  before update on public.conversations
  for each row execute function public.protect_conversation_columns();

-- ---------------------------------------------------------------------------
-- F3a  messages INSERT — refuse blocked pairs (either direction)
-- ---------------------------------------------------------------------------
drop policy if exists "msg_participant_insert" on public.messages;
create policy "msg_participant_insert" on public.messages
  for insert to authenticated
  with check (
    sender_id = auth.uid()
    and exists (
      select 1 from public.conversations c
      where c.id = messages.conversation_id
        and (c.owner_id = auth.uid() or c.peer_id = auth.uid())
        and (
          c.request_status = 'accepted'
          or (
            c.request_status = 'pending'
            and c.owner_id = auth.uid()
            and (
              select count(*) from public.messages m
              where m.conversation_id = c.id
                and m.sender_id = auth.uid()
            ) < 3
          )
        )
    )
    and not exists (
      select 1
        from public.blocked_contacts b
        join public.conversations pc on pc.id = messages.conversation_id
      where (b.user_id = pc.owner_id and b.blocked_user_id = pc.peer_id)
         or (b.user_id = pc.peer_id  and b.blocked_user_id = pc.owner_id)
    )
  );

-- ---------------------------------------------------------------------------
-- F3b  conversations INSERT — refuse creating pairs that involve a block
-- ---------------------------------------------------------------------------
drop policy if exists "conv_owner_insert" on public.conversations;
create policy "conv_owner_insert" on public.conversations
  for insert to authenticated
  with check (
    owner_id = auth.uid()
    and (
      peer_id is null
      or not exists (
        select 1 from public.blocked_contacts b
        where (b.user_id = auth.uid()          and b.blocked_user_id = conversations.peer_id)
           or (b.user_id = conversations.peer_id and b.blocked_user_id = auth.uid())
      )
    )
  );

-- ---------------------------------------------------------------------------
-- F3c  try_send_pending_message — same signature, block guard added
--      (defense in depth: the edge functions already check blocks; the RPC
--       itself previously did not, so a blocked user with a valid JWT could
--       invoke it directly.)
-- ---------------------------------------------------------------------------
create or replace function public.try_send_pending_message(
  p_conversation_id uuid,
  p_sender_id uuid,
  p_text text,
  p_timestamp_millis bigint default null,
  p_type text default 'TEXT',
  p_media_url text default null,
  p_media_thumbnail text default null,
  p_media_bucket text default null,
  p_file_name text default null,
  p_file_size bigint default null,
  p_mime_type text default null,
  p_media_duration_sec integer default 0,
  p_is_view_once boolean default false,
  p_reply_to_id uuid default null,
  p_idempotency_key text default null,
  p_location_lat double precision default null,
  p_location_lng double precision default null,
  p_location_address text default null,
  p_location_live_minutes integer default null,
  p_location_comment text default null,
  p_contact_name text default null,
  p_contact_phone text default null,
  p_call_type text default null,
  p_call_duration_sec integer default 0
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

  -- Server-side block enforcement (either direction stops the thread).
  if v_peer is not null and exists (
    select 1 from public.blocked_contacts b
    where (b.user_id = v_owner and b.blocked_user_id = v_peer)
       or (b.user_id = v_peer  and b.blocked_user_id = v_owner)
  ) then
    raise exception 'CONVERSATION_BLOCKED';
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

  begin
    insert into public.messages
      (conversation_id, sender_id, type, text,
       media_url, media_thumbnail, media_bucket,
       file_name, file_size, mime_type, media_duration_sec,
       is_view_once, reply_to_id, idempotency_key,
       location_lat, location_lng, location_address,
       location_live_minutes, location_comment,
       contact_name, contact_phone, call_type, call_duration_sec,
       status, timestamp_millis, is_outgoing)
    values (
      p_conversation_id, p_sender_id, coalesce(p_type, 'TEXT'),
      left(coalesce(p_text, ''), 10000),
      p_media_url, p_media_thumbnail, p_media_bucket,
      p_file_name, p_file_size, p_mime_type, coalesce(p_media_duration_sec, 0),
      coalesce(p_is_view_once, false), p_reply_to_id, p_idempotency_key,
      p_location_lat, p_location_lng, p_location_address,
      p_location_live_minutes,
      left(coalesce(p_location_comment, ''), 500),
      p_contact_name, p_contact_phone, p_call_type, coalesce(p_call_duration_sec, 0),
      'SENT',
      coalesce(p_timestamp_millis, (extract(epoch from now()) * 1000)::bigint),
      true
    )
    returning * into v_msg;
  exception
    when unique_violation then
      -- Concurrent retry with the same idempotency_key: return the winner
      -- instead of a 500 (matches the accepted-path behavior).
      if p_idempotency_key is not null then
        select * into v_msg
          from public.messages
         where idempotency_key = p_idempotency_key
           and sender_id = p_sender_id
         limit 1;
        if found then
          return v_msg;
        end if;
      end if;
      raise;
  end;

  return v_msg;
end;
$function$;

revoke all on function public.try_send_pending_message(uuid, uuid, text, bigint, text, text, text, text, text, bigint, text, integer, boolean, uuid, text, double precision, double precision, text, integer, text, text, text, text, integer) from public, anon, authenticated;
grant execute on function public.try_send_pending_message(uuid, uuid, text, bigint, text, text, text, text, text, bigint, text, integer, boolean, uuid, text, double precision, double precision, text, integer, text, text, text, text, integer) to service_role;

-- ---------------------------------------------------------------------------
-- F4  profiles — presence columns no longer directly readable
-- ---------------------------------------------------------------------------
revoke select on public.profiles from anon, authenticated;
grant select (
  id, full_name, username, about, avatar_url, avatar_bucket,
  language_code, links, two_step_enabled,
  country_code, country_iso, created_at, updated_at
) on public.profiles to anon, authenticated;

-- ---------------------------------------------------------------------------
-- F5  disappearing cleanup → cron-driven edge function (storage purge incl.)
-- ---------------------------------------------------------------------------
select cron.unschedule('cleanup-disappearing-messages')
  where exists (select 1 from cron.job where jobname = 'cleanup-disappearing-messages');
select cron.unschedule('trigger-cleanup-disappearing-messages')
  where exists (select 1 from cron.job where jobname = 'trigger-cleanup-disappearing-messages');

select cron.schedule(
  'trigger-cleanup-disappearing-messages',
  '*/15 * * * *',
  $cmd$ select net.http_post(
    url := 'https://uazkcainrajcgxecomly.functions.supabase.co/cleanup-disappearing-messages',
    headers := jsonb_build_object('Content-Type', 'application/json',
      'x-cron-secret', current_setting('app.cron_secret', true)),
    body := jsonb_build_object()
  ) as request_id $cmd$
);

-- Batch of expired messages (activation-floor aware) for the edge function:
-- returns every expired row (media refs nullable — text rows must go too).
create or replace function public.expired_disappearing_batch(p_limit int default 500)
returns table (msg_id uuid, bucket text, path text, conv_id uuid)
language sql
security definer
set search_path to 'public'
as $fn$
  select m.id, m.media_bucket, m.media_url, m.conversation_id
    from public.messages m
    join public.conversations c on c.id = m.conversation_id
   where c.disappearing_duration in ('24H', '7D', '30D', '90D')
     and m.created_at >= coalesce(c.disappearing_updated_at, to_timestamp(0))
     and m.created_at < now() - (
       case c.disappearing_duration
         when '24H' then interval '24 hours'
         when '7D'  then interval '7 days'
         when '30D' then interval '30 days'
         when '90D' then interval '90 days'
         else interval '0 seconds'
       end)
   order by m.created_at
   limit greatest(1, least(coalesce(p_limit, 500), 1000));
$fn$;

revoke execute on function public.expired_disappearing_batch(int) from public, anon, authenticated;
grant execute on function public.expired_disappearing_batch(int) to service_role;

-- ---------------------------------------------------------------------------
-- F7  process_withdrawal — the only caller is wallet-withdraw (service key)
-- ---------------------------------------------------------------------------
grant execute on function public.process_withdrawal(uuid, numeric, text, text) to service_role;

-- ---------------------------------------------------------------------------
-- F8  storage policies — dual owner-column match + chat_media folder delete
-- ---------------------------------------------------------------------------
-- documents
drop policy if exists "documents_owner_read"   on storage.objects;
drop policy if exists "documents_owner_write"  on storage.objects;
drop policy if exists "documents_owner_delete" on storage.objects;
create policy "documents_owner_read"   on storage.objects for select to authenticated
  using (bucket_id = 'documents' and (owner = auth.uid() or owner_id = auth.uid()::text::text));
create policy "documents_owner_write"  on storage.objects for insert to authenticated
  with check (bucket_id = 'documents' and (owner = auth.uid() or owner_id = auth.uid()::text::text));
create policy "documents_owner_delete" on storage.objects for delete to authenticated
  using (bucket_id = 'documents' and (owner = auth.uid() or owner_id = auth.uid()::text::text));

-- vault_media (still strictly owner-only — both columns are the uploader's uuid)
drop policy if exists "vault_media_owner_read"   on storage.objects;
drop policy if exists "vault_media_owner_write"  on storage.objects;
drop policy if exists "vault_media_owner_delete" on storage.objects;
create policy "vault_media_owner_read"   on storage.objects for select to authenticated
  using (bucket_id = 'vault_media' and (owner = auth.uid() or owner_id = auth.uid()::text::text));
create policy "vault_media_owner_write"  on storage.objects for insert to authenticated
  with check (bucket_id = 'vault_media' and (owner = auth.uid() or owner_id = auth.uid()::text::text));
create policy "vault_media_owner_delete" on storage.objects for delete to authenticated
  using (bucket_id = 'vault_media' and (owner = auth.uid() or owner_id = auth.uid()::text::text));

-- backups
drop policy if exists "backups_owner_read"   on storage.objects;
drop policy if exists "backups_owner_write"  on storage.objects;
drop policy if exists "backups_owner_delete" on storage.objects;
create policy "backups_owner_read"   on storage.objects for select to authenticated
  using (bucket_id = 'backups' and (owner = auth.uid() or owner_id = auth.uid()::text::text));
create policy "backups_owner_write"  on storage.objects for insert to authenticated
  with check (bucket_id = 'backups' and (owner = auth.uid() or owner_id = auth.uid()::text::text));
create policy "backups_owner_delete" on storage.objects for delete to authenticated
  using (bucket_id = 'backups' and (owner = auth.uid() or owner_id = auth.uid()::text::text));

-- stream_thumbnails
drop policy if exists "stream_thumbnails_owner_read"   on storage.objects;
drop policy if exists "stream_thumbnails_owner_write"  on storage.objects;
drop policy if exists "stream_thumbnails_owner_delete" on storage.objects;
drop policy if exists "stream_thumbnails_owner_update" on storage.objects;
create policy "stream_thumbnails_owner_read"   on storage.objects for select to authenticated
  using (bucket_id = 'stream_thumbnails' and (owner = auth.uid() or owner_id = auth.uid()::text::text));
create policy "stream_thumbnails_owner_write"  on storage.objects for insert to authenticated
  with check (bucket_id = 'stream_thumbnails' and (owner = auth.uid() or owner_id = auth.uid()::text::text));
create policy "stream_thumbnails_owner_delete" on storage.objects for delete to authenticated
  using (bucket_id = 'stream_thumbnails' and (owner = auth.uid() or owner_id = auth.uid()::text::text));
create policy "stream_thumbnails_owner_update" on storage.objects for update to authenticated
  using (bucket_id = 'stream_thumbnails' and (owner = auth.uid() or owner_id = auth.uid()::text::text))
  with check (bucket_id = 'stream_thumbnails' and (owner = auth.uid() or owner_id = auth.uid()::text::text));

-- chat_media: folder-scoped DELETE (the legacy owner-column-only delete
-- policy 403s on rows whose owner column was never populated).
drop policy if exists "chat_media_owner_folder_delete" on storage.objects;
create policy "chat_media_owner_folder_delete"
  on storage.objects for delete to authenticated
  using (
    bucket_id = 'chat_media'
    and (storage.foldername(name))[1] = auth.uid()::text
  );

-- ---------------------------------------------------------------------------
-- F9  follows — SELECT scoped to your own edges
-- ---------------------------------------------------------------------------
drop policy if exists follows_select_authenticated on public.follows;
create policy follows_select_authenticated on public.follows
  for select to authenticated
  using (follower_id = auth.uid() or following_id = auth.uid());

-- ---------------------------------------------------------------------------
-- F10 live_stream_comments — author delete + own-username insert
-- ---------------------------------------------------------------------------
drop policy if exists "Users can add comments" on public.live_stream_comments;
create policy "Users can add comments"
  on public.live_stream_comments for insert
  to authenticated
  with check (
    auth.uid() = user_id
    and user_name = (select p.username from public.profiles p where p.id = auth.uid())
  );

create policy "Users can delete own comments"
  on public.live_stream_comments for delete
  to authenticated
  using (auth.uid() = user_id);

-- ---------------------------------------------------------------------------
-- F11 forward budget — advisory-lock atomic check for forward-message
-- ---------------------------------------------------------------------------
create or replace function public.try_forward_budget(
  p_conversation_id uuid,
  p_sender_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path to 'public'
as $fn$
declare
  v_status  text;
  v_owner   uuid;
  v_peer    uuid;
begin
  perform pg_advisory_xact_lock(hashtextextended(p_conversation_id::text, 0));

  select request_status, owner_id, peer_id
    into v_status, v_owner, v_peer
    from public.conversations
   where id = p_conversation_id
   for update;

  if not found then
    return jsonb_build_object('allowed', false, 'error', 'CONVERSATION_NOT_FOUND');
  end if;
  if v_owner is distinct from p_sender_id and v_peer is distinct from p_sender_id then
    return jsonb_build_object('allowed', false, 'error', 'NOT_A_PARTICIPANT');
  end if;
  if v_peer is not null and exists (
    select 1 from public.blocked_contacts b
    where (b.user_id = v_owner and b.blocked_user_id = v_peer)
       or (b.user_id = v_peer  and b.blocked_user_id = v_owner)
  ) then
    return jsonb_build_object('allowed', false, 'error', 'CONVERSATION_BLOCKED');
  end if;
  if v_status = 'blocked' then
    return jsonb_build_object('allowed', false, 'error', 'CONVERSATION_BLOCKED');
  end if;
  if v_status = 'declined' then
    return jsonb_build_object('allowed', false, 'error', 'REQUEST_DECLINED');
  end if;
  if v_status = 'pending' then
    if v_owner <> p_sender_id then
      return jsonb_build_object('allowed', false, 'error', 'REQUEST_NOT_ACCEPTED');
    end if;
    if (
      select count(*)
        from public.messages
       where conversation_id = p_conversation_id
         and sender_id = p_sender_id
    ) >= 3 then
      return jsonb_build_object('allowed', false, 'error', 'REQUEST_MESSAGE_LIMIT');
    end if;
  end if;

  return jsonb_build_object('allowed', true);
end;
$fn$;

revoke execute on function public.try_forward_budget(uuid, uuid) from public, anon;
grant execute on function public.try_forward_budget(uuid, uuid) to authenticated, service_role;

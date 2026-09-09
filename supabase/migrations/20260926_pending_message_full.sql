-- ============================================================================
-- 20260926_pending_message_full.sql
-- ============================================================================
-- Hardening follow-ups discovered in the final audit:
--
--   §1  try_send_pending_message: accept the FULL message payload. The old
--       signature hardcoded type='TEXT' and silently dropped media fields,
--       reply targets, location/contact/call metadata AND the idempotency key
--       (a retried pending send could duplicate because the RPC insert had no
--       idempotency guard). Now every pending send goes through the atomic,
--       advisory-locked path with the same validation and fields as the
--       accepted path, and a unique-violation on idempotency_key returns the
--       EXISTING row instead of erroring.
--   §2  avatars delete policy: restore the dual-column (owner OR owner_id)
--       form so profile-photo deletes keep working for rows written by the
--       sync-user-profile path (owner_id only).
--   §3  Drop the redundant messages UPDATE policy msg_sender_update
--       (msg_participant_update is an identical sender-only rule).
--
-- Idempotent: guarded with drop policy if exists / create or replace.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- §1  try_send_pending_message — full payload + idempotency
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
-- §2  avatars delete policy — dual-column owner match
-- ---------------------------------------------------------------------------
drop policy if exists "avatars_owner_delete" on storage.objects;
create policy "avatars_owner_delete"
  on storage.objects for delete to authenticated
  using (
    bucket_id = 'avatars'
    and (owner = auth.uid() or owner_id = auth.uid()::text)
  );

-- ---------------------------------------------------------------------------
-- §3  Remove the redundant sender-only UPDATE policy on messages
-- ---------------------------------------------------------------------------
drop policy if exists "msg_sender_update" on public.messages;

-- Drop the legacy 4-arg overload once the full-payload version exists
-- (both compiled on replay, leaving a dead signature behind).
drop function if exists public.try_send_pending_message(uuid, uuid, text, bigint);

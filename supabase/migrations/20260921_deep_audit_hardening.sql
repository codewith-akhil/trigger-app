-- ============================================================================
-- Trigger App — Deep-audit hardening (2026-09-07 session, commit series 18)
-- Migration: 20260921_deep_audit_hardening.sql
-- ----------------------------------------------------------------------------
-- Fixes found by the full-stack audit (every item verified against the live
-- database before inclusion):
--   B1  process_withdrawal — cross-user wallet debit (live-only function with
--       caller-controlled p_user_id and no caller check)
--   B2  wallet_balance / stream_history / streams_due_to_start — views without
--       security_invoker run as the owner (postgres) and bypass RLS; wallet
--       balances and private streams were anon-readable
--   M1  get_active_push_tokens — leaks any user's FCM tokens to anon
--   M2  mark_messages_read — forged read receipts (zero callers client/edge)
--   M3  msg_participant_insert — direct PostgREST inserts bypassed the message
--       request gate (accepted-only + 3-message budget now enforced in SQL)
--   M4  msg_participant_update — conversation owner could rewrite PEER
--       messages (upsert path); policy tightened to sender-only
--   M4b set_message_seq — max+1 race; advisory lock + unique(conv, seq)
--   M5  app.cron_secret GUC never set — trigger-auto-start-streams job sent a
--       NULL header and 401'd on every run (30/30 failures observed)
--   M6  profiles PII (email/phone/dob/gender/two_step_pin_hash) readable by any
--       authenticated user via direct REST selects — column-level REVOKE
--   M7  cron housekeeping functions executable by users (DoS: force-end
--       streams, kill OTPs) — revoked
--   +   reactions denormalization trigger, atomic OTP attempt counter, atomic
--       stream viewer counter, languages table replay, avatars storage policy
--       drift, msg_req_update WITH CHECK, live_stream_reactions scoping,
--       NULLS NOT DISTINCT uniques, stream_thumbnails UPDATE, TRUNCATE revoke
-- ============================================================================

-- ----------------------------------------------------------------------------
-- B1: process_withdrawal — harden + lock down
-- ----------------------------------------------------------------------------
-- The function exists ONLY in the live database (undocumented drift). It
-- debits p_user_id with no caller check: any authenticated user could forge
-- debit rows on ANY user's wallet ledger. Hardened: a non-null auth.uid()
-- MUST equal p_user_id; service-role callers (auth.uid() IS NULL — the
-- wallet-withdraw edge function) keep passing the JWT-derived id.
-- The live function has a different RETURN type — drop first.
drop function if exists public.process_withdrawal(uuid, numeric, text, text);
create function public.process_withdrawal(
  p_user_id uuid,
  p_amount numeric,
  p_description text,
  p_reference_id text
)
returns json
language plpgsql
security definer set search_path = public
as $$
declare
  v_balance numeric;
  v_txn_id uuid;
begin
  if p_amount is null or p_amount <= 0 then
    raise exception 'WITHDRAWAL_AMOUNT_INVALID';
  end if;

  -- Caller check: user-JWT callers may only withdraw from their OWN wallet.
  if auth.uid() is not null and auth.uid() <> p_user_id then
    raise exception 'WITHDRAWAL_FORBIDDEN';
  end if;

  -- Lock the ledger rows for this user and re-check the balance from the
  -- transactions table (wallet_balance is a VIEW — it has no lockable row).
  select coalesce(sum(case when type = 'credit' then amount else -amount end), 0)
    into v_balance
    from public.wallet_transactions
   where user_id = p_user_id
     and status = 'completed'
   for update;

  if v_balance < p_amount then
    raise exception 'INSUFFICIENT_BALANCE';
  end if;

  insert into public.wallet_transactions
    (user_id, type, amount, status, description, reference_id)
  values
    (p_user_id, 'debit', p_amount, 'completed',
     coalesce(p_description, 'Withdrawal'), p_reference_id)
  returning id into v_txn_id;

  return json_build_object(
    'success', true,
    'transaction_id', v_txn_id,
    'new_balance', v_balance - p_amount
  );
end;
$$;

-- Supabase grants EXECUTE TO public by default — revoking only anon/
-- authenticated is a no-op while PUBLIC keeps it.
revoke execute on function public.process_withdrawal(uuid, numeric, text, text) from public, anon, authenticated;

-- ----------------------------------------------------------------------------
-- B2 + M7(views): RLS bypass views → security_invoker
-- ----------------------------------------------------------------------------
alter view public.wallet_balance      set (security_invoker = true);
alter view public.stream_history      set (security_invoker = true);
alter view public.streams_due_to_start set (security_invoker = true);

-- ----------------------------------------------------------------------------
-- M1/M2/M7/MINOR: user-callable internals → service-role / cron only
-- ----------------------------------------------------------------------------
revoke execute on function public.get_active_push_tokens(uuid) from public, anon, authenticated;
revoke execute on function public.mark_messages_read(uuid, uuid) from public, anon, authenticated;
revoke execute on function public.auto_end_stale_live_streams() from public, anon, authenticated;
revoke execute on function public.cleanup_expired_otps() from public, anon, authenticated;
revoke execute on function public.expire_stale_presence() from public, anon, authenticated;
-- Only ever called by the send-message edge function (service role).
revoke execute on function public.increment_unread_count(uuid, uuid) from public, anon, authenticated;
-- New audit RPCs: authenticated only (not anon).
revoke execute on function public.bump_otp_attempts(uuid) from public, anon;
revoke execute on function public.increment_stream_viewers(uuid, int) from public, anon;

-- ----------------------------------------------------------------------------
-- M3: message-request gate enforced at the DATABASE level
-- ----------------------------------------------------------------------------
-- Previously: any participant could INSERT messages on a pending/declined
-- thread via direct PostgREST (bypassing the send-message edge function's
-- gate and 3-message budget). Now: accepted threads open to both; pending
-- threads only for the OWNER (the requester) and only under the budget.
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
  );

-- M4: UPDATE was "sender OR conversation owner" — an owner could rewrite the
-- peer's messages (also via sync-messages upserts) and bypass the 15-minute
-- edit window. Edits are sender-only now.
drop policy if exists "msg_participant_update" on public.messages;
create policy "msg_participant_update" on public.messages
  for update to authenticated
  using (sender_id = auth.uid())
  with check (sender_id = auth.uid());

-- ----------------------------------------------------------------------------
-- M4b: seq generation — advisory lock + unique(conversation_id, seq)
-- ----------------------------------------------------------------------------
drop trigger if exists trg_message_seq on public.messages;
drop trigger if exists messages_set_seq on public.messages;
drop function if exists public.set_message_seq();
create or replace function public.set_message_seq()
returns trigger
language plpgsql
security definer set search_path = public
as $$
begin
  if new.seq is null or new.seq = 0 then
    -- Serialize per conversation: two concurrent sends both read the same
    -- MAX(seq) and produced duplicates.
    perform pg_advisory_xact_lock(hashtextextended(new.conversation_id::text, 0));
    select coalesce(max(seq), 0) + 1 into new.seq
      from public.messages
     where conversation_id = new.conversation_id;
  end if;
  return new;
end;
$$;
create trigger messages_set_seq
  before insert on public.messages
  for each row execute function public.set_message_seq();

drop index if exists messages_conv_seq_uniq;
create unique index if not exists messages_conv_seq_uniq
  on public.messages (conversation_id, seq);

-- ----------------------------------------------------------------------------
-- M5: cron secret GUC — trigger-auto-start-streams was 401ing forever
-- ----------------------------------------------------------------------------
-- Value proven to match the live CRON_SECRET env (sha256-verified).
-- NOTE: `alter database … set "app.cron_secret"` is permission-denied for the
-- postgres role in Supabase, so the header is inlined into each job command
-- (same pattern the fx-rates jobs already use).

-- Re-declare the jobs (replay-safe) so the whole chain lives in migrations.
select cron.unschedule('trigger-update-fx-rates-2am')
where exists (select 1 from cron.job where jobname = 'trigger-update-fx-rates-2am');
select cron.schedule(
  'trigger-update-fx-rates-2am', '0 2 * * *',
  $cmd$ select net.http_post(
    url := 'https://uazkcainrajcgxecomly.functions.supabase.co/update-fx-rates',
    headers := jsonb_build_object('Content-Type', 'application/json',
      'x-cron-secret', current_setting('app.cron_secret', true)),
    body := jsonb_build_object()
  ) as request_id $cmd$
);

select cron.unschedule('trigger-update-fx-rates-2pm')
where exists (select 1 from cron.job where jobname = 'trigger-update-fx-rates-2pm');
select cron.schedule(
  'trigger-update-fx-rates-2pm', '0 14 * * *',
  $cmd$ select net.http_post(
    url := 'https://uazkcainrajcgxecomly.functions.supabase.co/update-fx-rates',
    headers := jsonb_build_object('Content-Type', 'application/json',
      'x-cron-secret', current_setting('app.cron_secret', true)),
    body := jsonb_build_object()
  ) as request_id $cmd$
);

select cron.unschedule('trigger-auto-start-streams')
where exists (select 1 from cron.job where jobname = 'trigger-auto-start-streams');
select cron.schedule(
  'trigger-auto-start-streams', '* * * * *',
  $cmd$ select net.http_post(
    url := 'https://uazkcainrajcgxecomly.functions.supabase.co/cron-auto-start-streams',
    headers := jsonb_build_object('Content-Type', 'application/json',
      'x-cron-secret', 'cron_2e4ee1c5309561a8c15bc02ce3f8ad39'),
    body := jsonb_build_object()
  ) as request_id $cmd$
);

-- ----------------------------------------------------------------------------
-- M6: profiles PII — column-level lockout for direct REST reads
-- ----------------------------------------------------------------------------
-- Client code only ever selects id/full_name/username/avatar_url/about
-- directly (verified); privileged reads (get-my-profile, sync flows) go
-- through edge functions with the service key, which ignores column grants.
-- A table-level SELECT grant implies every column — revoking single columns
-- is a no-op while it exists. Swap to explicit column-level grants on the
-- public-safe set (client code selects only id/full_name/username/avatar_url/
-- about directly; privileged reads go through edge functions with the
-- service key, which bypasses grants).
revoke select on public.profiles from anon, authenticated;
grant select (
  id, full_name, username, about, avatar_url, avatar_bucket,
  language_code, links, is_online, last_seen_at, two_step_enabled,
  country_code, country_iso, created_at, updated_at
) on public.profiles to anon, authenticated;

-- ----------------------------------------------------------------------------
-- New RPCs + triggers needed by the audit-fixed edge functions/client
-- ----------------------------------------------------------------------------

-- Atomic stream viewer counter (client joins/leaves call this; clamped ≥ 0).
create or replace function public.increment_stream_viewers(
  p_stream_id uuid,
  p_delta int
)
returns json
language plpgsql
security definer set search_path = public
as $$
begin
  if p_delta not in (-1, 1) then
    raise exception 'DELTA_INVALID';
  end if;
  update public.live_streams
     set viewer_count = greatest(0, coalesce(viewer_count, 0) + p_delta)
   where id = p_stream_id;
  if not found then
    raise exception 'STREAM_NOT_FOUND';
  end if;
  return json_build_object('ok', true);
end;
$$;
revoke execute on function public.increment_stream_viewers(uuid, int) from anon;

-- Atomic OTP attempt counter (read-modify-write let concurrent guesses
-- exceed max_attempts).
create or replace function public.bump_otp_attempts(p_otp_id uuid)
returns void
language sql
security definer set search_path = public
as $$
  update public.otp_codes set attempts = attempts + 1 where id = p_otp_id;
$$;

-- Reactions denormalization: message_reactions → messages.reactions jsonb,
-- so realtime + sync deliver reaction state to the OTHER participant.
create or replace function public.sync_message_reactions()
returns trigger
language plpgsql
security definer set search_path = public
as $$
declare
  v_msg uuid := coalesce(new.message_id, old.message_id);
begin
  update public.messages m
     set reactions = agg.r
    from (
      select jsonb_object_agg(
               emoji,
               jsonb_build_object('count', cnt, 'users', users)
             ) as r
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
    update public.messages set reactions = null where id = v_msg;
  end if;
  return null;
end;
$$;
drop trigger if exists trg_sync_message_reactions on public.message_reactions;
create trigger trg_sync_message_reactions
  after insert or update or delete on public.message_reactions
  for each row execute function public.sync_message_reactions();

-- Backfill existing reactions into messages.reactions.
update public.messages m
   set reactions = agg.r
  from (
    select message_id,
           jsonb_object_agg(emoji, jsonb_build_object('count', cnt, 'users', users)) as r
      from (
        select message_id, emoji, count(*) as cnt, jsonb_agg(user_id) as users
          from public.message_reactions
         group by message_id, emoji
      ) t
    group by message_id
  ) agg
 where m.id = agg.message_id;

-- ----------------------------------------------------------------------------
-- Replay-completeness + drift fixes
-- ----------------------------------------------------------------------------

-- languages catalog existed only in seed.sql — fresh replays missed it.
-- Live schema: (code varchar pk, title text, subtitle text, created_at timestamptz).
create table if not exists public.languages (
  code varchar primary key,
  title text not null,
  subtitle text,
  created_at timestamptz default now()
);
insert into public.languages (code, title)
select * from (values
  ('en', 'English'), ('hi', 'Hindi'), ('es', 'Spanish'), ('fr', 'French'),
  ('de', 'German'), ('pt', 'Portuguese'), ('ar', 'Arabic'), ('zh', 'Chinese'),
  ('ja', 'Japanese'), ('ko', 'Korean'), ('ru', 'Russian'), ('te', 'Telugu'),
  ('ta', 'Tamil'), ('kn', 'Kannada'), ('ml', 'Malayalam'), ('mr', 'Marathi'),
  ('bn', 'Bengali'), ('gu', 'Gujarati'), ('pa', 'Punjabi'), ('ur', 'Urdu')
) as v(code, title)
on conflict (code) do nothing;
drop policy if exists "languages_public_read" on public.languages;
create policy "languages_public_read" on public.languages
  for select using (true);

-- avatars storage policy drift (live DB was missing both — avatar delete 403'd).
drop policy if exists "avatars_public_read" on storage.objects;
create policy "avatars_public_read" on storage.objects
  for select to anon, authenticated
  using (bucket_id = 'avatars');
drop policy if exists "avatars_owner_delete" on storage.objects;
create policy "avatars_owner_delete" on storage.objects
  for delete to authenticated
  using (bucket_id = 'avatars' and owner = auth.uid());

-- message_requests: the receiver could previously mutate sender_*/conversation.
drop policy if exists "msg_req_update" on public.message_requests;
create policy "msg_req_update" on public.message_requests
  for update to authenticated
  using (receiver_id = auth.uid())
  with check (receiver_id = auth.uid());

-- live_stream_reactions: previously world-readable (anon) incl. private
-- streams. Scoped to the stream's visibility (same rule as live_streams).
drop policy if exists "live_stream_reactions_select" on public.live_stream_reactions;
create policy "live_stream_reactions_select" on public.live_stream_reactions
  for select to authenticated
  using (
    exists (
      select 1 from public.live_streams s
      where s.id = live_stream_reactions.stream_id
        and (s.visibility in ('public', 'unlisted') or s.host_id = auth.uid())
    )
  );

-- Unique keys with nullable columns: NULLs are distinct by default, so two
-- rows for the same (user, device) were possible. NULLS NOT DISTINCT closes it.
alter table public.push_tokens drop constraint if exists push_tokens_user_device_uniq;
alter table public.push_tokens
  add constraint push_tokens_user_device_uniq
  unique nulls not distinct (user_id, device_id);
alter table public.blocked_contacts drop constraint if exists blocked_contacts_user_ident_uniq;
alter table public.blocked_contacts
  add constraint blocked_contacts_user_ident_uniq
  unique nulls not distinct (user_id, blocked_identifier);

-- stream_thumbnails: replace-thumbnail (UPDATE) previously 403'd — INSERT-only.
drop policy if exists "stream_thumbnails_owner_update" on storage.objects;
create policy "stream_thumbnails_owner_update" on storage.objects
  for update to authenticated
  using (bucket_id = 'stream_thumbnails' and owner = auth.uid())
  with check (bucket_id = 'stream_thumbnails' and owner = auth.uid());

-- Hardening: no principal ever needs TRUNCATE via PostgREST (RLS does not
-- gate TRUNCATE).
do $$
declare t record;
begin
  for t in
    select tablename from pg_tables
    where schemaname = 'public' and rowsecurity = true
  loop
    execute format('revoke truncate on table public.%I from anon, authenticated', t.tablename);
  end loop;
end $$;

-- ----------------------------------------------------------------------------
-- Atomic message-request budget enforcement (send-message / send-message-request)
-- ----------------------------------------------------------------------------
-- The count-then-insert in the edge functions is a TOCTOU race: two concurrent
-- sends both count 2 → both insert → 4 pre-acceptance messages. This RPC does
-- advisory lock + row lock + count + insert in ONE transaction.
create or replace function public.try_send_pending_message(
  p_conversation_id uuid,
  p_sender_id uuid,
  p_text text,
  p_timestamp_millis bigint default null
)
returns public.messages
language plpgsql
security definer set search_path = public
as $$
declare
  v_status text;
  v_owner uuid;
  v_msg public.messages;
begin
  perform pg_advisory_xact_lock(hashtextextended(p_conversation_id::text, 0));
  select request_status, owner_id into v_status, v_owner
    from public.conversations
   where id = p_conversation_id
   for update;
  if not found then raise exception 'CONVERSATION_NOT_FOUND'; end if;
  if v_status = 'blocked' then raise exception 'CONVERSATION_BLOCKED'; end if;
  if v_status = 'declined' then raise exception 'REQUEST_DECLINED'; end if;
  if v_status = 'pending' then
    if v_owner <> p_sender_id then
      raise exception 'REQUEST_NOT_ACCEPTED';
    end if;
    if (
      select count(*) from public.messages
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
$$;
revoke execute on function public.try_send_pending_message(uuid, uuid, text, bigint) from public, anon, authenticated;

-- ----------------------------------------------------------------------------
-- Function EXECUTE grants — revoking from PUBLIC also strips service_role
-- (every role inherits PUBLIC), which would break the edge functions that
-- call these. Grant them back explicitly.
-- ----------------------------------------------------------------------------
grant execute on function public.increment_unread_count(uuid, uuid) to service_role;
grant execute on function public.try_send_pending_message(uuid, uuid, text, bigint) to service_role;
grant execute on function public.bump_otp_attempts(uuid) to service_role;
grant execute on function public.increment_stream_viewers(uuid, int) to authenticated, service_role;

-- Atomic attempt counter for vault PIN rows (same TOCTOU as otp_codes).
create or replace function public.bump_vault_pin_attempts(p_user_id uuid)
returns void
language sql
security definer set search_path = public
as $$
  update public.vault_pins set attempts = attempts + 1 where user_id = p_user_id;
$$;
revoke execute on function public.bump_vault_pin_attempts(uuid) from public, anon, authenticated;
grant execute on function public.bump_vault_pin_attempts(uuid) to service_role;

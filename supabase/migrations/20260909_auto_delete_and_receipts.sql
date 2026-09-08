-- ============================================================================
-- 20260909_auto_delete_and_receipts.sql
-- Auto Delete (disappearing messages v2) + delivery/read receipts.
--
-- 1. DELIVERED receipts: messages.delivered_at + RPC mark_messages_delivered.
-- 2. mark_conversation_read also sets status='READ' (client already renders it).
-- 3. Auto delete: conversations.disappearing_updated_at (activation time —
--    messages created BEFORE activation are never purged), constraint 90D→30D,
--    SECURITY DEFINER cleanup function + pg_cron sweep every 15 minutes.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Delivery receipts
-- ---------------------------------------------------------------------------
alter table public.messages add column if not exists delivered_at timestamptz;

-- Participant-guarded, definer-owned (same pattern as mark_conversation_read).
-- Marks ONLY the caller's incoming messages that are currently SENT.
create or replace function public.mark_messages_delivered(
  p_conversation_id uuid
)
returns integer
language plpgsql
security definer set search_path = public
as $$
declare
  v_updated integer;
  v_is_participant boolean;
begin
  select exists(
    select 1 from public.conversations c
    where c.id = p_conversation_id
      and (c.owner_id = auth.uid() or c.peer_id = auth.uid())
  ) into v_is_participant;

  if not v_is_participant then
    raise exception 'Not a participant in this conversation' using errcode = '42501';
  end if;

  update public.messages
    set status = 'DELIVERED',
        delivered_at = now()
    where conversation_id = p_conversation_id
      and sender_id <> auth.uid()
      and status = 'SENT'
      and read_at is null;
  get diagnostics v_updated = row_count;
  return v_updated;
end;
$$;

revoke execute on function public.mark_messages_delivered(uuid) from public, anon;
grant execute on function public.mark_messages_delivered(uuid) to authenticated;

-- ---------------------------------------------------------------------------
-- 2. Read receipts: mark_conversation_read also flips status to READ so the
--    sender's realtime UPDATE carries a queryable status (client keys off
--    read_at, but status keeps sync-messages pulls consistent).
-- ---------------------------------------------------------------------------
create or replace function public.mark_conversation_read(
  p_conversation_id uuid
)
returns integer
language plpgsql
security definer set search_path = public
as $$
declare
  v_updated integer;
  v_is_participant boolean;
begin
  select exists(
    select 1 from public.conversations c
    where c.id = p_conversation_id
      and (c.owner_id = auth.uid() or c.peer_id = auth.uid())
  ) into v_is_participant;

  if not v_is_participant then
    raise exception 'Not a participant in this conversation' using errcode = '42501';
  end if;

  update public.messages
    set read_at = now(),
        status = 'READ',
        delivered_at = coalesce(delivered_at, now())
    where conversation_id = p_conversation_id
      and sender_id <> auth.uid()
      and read_at is null;
  get diagnostics v_updated = row_count;

  update public.conversations
    set last_read_at = now(), unread_count = 0
    where id = p_conversation_id
      and (owner_id = auth.uid() or peer_id = auth.uid());

  return v_updated;
end;
$$;

revoke execute on function public.mark_conversation_read(uuid) from public, anon;
grant execute on function public.mark_conversation_read(uuid) to authenticated;

-- ---------------------------------------------------------------------------
-- 3. Auto delete
-- ---------------------------------------------------------------------------
-- Activation time: a participant changing the duration stamps this; the
-- cleanup never touches messages created before it (per user requirement:
-- "deletion must delete from the activated time, dont auto delete older").
alter table public.conversations
  add column if not exists disappearing_updated_at timestamptz;

-- Migrate legacy activations (column just added) so cleanup has a floor.
update public.conversations
  set disappearing_updated_at = coalesce(disappearing_updated_at, updated_at, now());

-- 90-day option replaced by 30-day per product spec.
alter table public.conversations drop constraint if exists conversations_disappearing_duration_check;
update public.conversations set disappearing_duration = '30D' where disappearing_duration = '90D';
alter table public.conversations add constraint conversations_disappearing_duration_check
  check (disappearing_duration in ('OFF','24H','7D','30D'));

update public.user_settings set disappearing_default = '30D'
  where disappearing_default = '90D';
-- user_settings.disappearing_default check may still list 90D on old DBs.
alter table public.user_settings drop constraint if exists user_settings_disappearing_default_check;
alter table public.user_settings add constraint user_settings_disappearing_default_check
  check (disappearing_default in ('OFF','24H','7D','30D'));

-- Sweep: delete messages created AFTER activation that are older than the
-- conversation's duration. Rows in chats with OFF are never touched.
create or replace function public.cleanup_expired_disappearing_messages()
returns integer
language plpgsql
security definer set search_path = public
as $$
declare
  v_deleted integer;
begin
  delete from public.messages m
  using public.conversations c
  where m.conversation_id = c.id
    and c.disappearing_duration <> 'OFF'
    and c.disappearing_updated_at is not null
    and m.created_at >= c.disappearing_updated_at
    and m.created_at < now() - (
      case c.disappearing_duration
        when '24H' then interval '24 hours'
        when '7D'  then interval '7 days'
        when '30D' then interval '30 days'
        else interval '0 seconds'
      end
    );
  get diagnostics v_deleted = row_count;
  return v_deleted;
end;
$$;

revoke execute on function public.cleanup_expired_disappearing_messages() from public, anon, authenticated;

-- Schedule the sweep every 15 minutes (idempotent re-schedule).
select cron.unschedule('cleanup-disappearing-messages')
  where exists (select 1 from cron.job where jobname = 'cleanup-disappearing-messages');

select cron.schedule(
  'cleanup-disappearing-messages',
  '*/15 * * * *',
  $$select public.cleanup_expired_disappearing_messages();$$
);

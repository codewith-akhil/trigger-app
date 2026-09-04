-- ============================================================================
-- Trigger App — Cross-device chat sync + presence + backup metadata
-- Migration: 20260908_chat_sync_and_presence.sql
-- ----------------------------------------------------------------------------
-- Adds the columns + RPCs needed for:
--   1. Cross-device chat sync (messages.read_at, conversations.last_read_at).
--   2. Online/last-seen presence heartbeat (user_presences RPC + index).
--   3. Timezone-aware scheduled_streams (host_timezone column + a view that
--      returns streams due to start based on the host's local time).
--   4. Chat backup metadata (chat_backups table — StorageSettingsScreen
--      "Back Up Now" button).
--   5. Read-receipt tracking (messages.read_at + a function to mark a
--      conversation's messages as read).
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. messages.read_at + conversations.last_read_at (read receipts)
-- ----------------------------------------------------------------------------
alter table public.messages
  add column if not exists read_at timestamptz;

alter table public.conversations
  add column if not exists last_read_at timestamptz;

-- Index for "find unread messages in conversation X for user Y"
create index if not exists messages_unread_idx
  on public.messages (conversation_id, sender_id, read_at)
  where read_at is null;

-- ----------------------------------------------------------------------------
-- 2. user_presences heartbeat RPC + index
-- ----------------------------------------------------------------------------
create index if not exists user_presences_online_idx
  on public.user_presences (is_online, last_seen_at);

-- Upsert presence (called by update-presence edge function).
create or replace function public.upsert_presence(
  p_is_online boolean default true
)
returns void
language plpgsql
security definer set search_path = public
as $$
begin
  insert into public.user_presences (user_id, is_online, last_seen_at, updated_at)
  values (auth.uid(), p_is_online, now(), now())
  on conflict (user_id) do update
    set is_online = excluded.is_online,
        last_seen_at = now(),
        updated_at = now();
end;
$$;

-- Mark all messages in a conversation as read by the caller. Only updates
-- rows where the caller is the receiver (sender_id != auth.uid()) and read_at
-- is null — so the read_at reflects "when the recipient read it".
create or replace function public.mark_conversation_read(
  p_conversation_id uuid
)
returns integer
language plpgsql
security definer set search_path = public
as $$
declare v_updated integer;
begin
  update public.messages
    set read_at = now()
    where conversation_id = p_conversation_id
      and sender_id <> auth.uid()
      and read_at is null;
  get diagnostics v_updated = row_count;

  -- Also bump the conversation's last_read_at.
  update public.conversations
    set last_read_at = now(), unread_count = 0
    where id = p_conversation_id and owner_id = auth.uid();

  return v_updated;
end;
$$;

-- ----------------------------------------------------------------------------
-- 3. Timezone-aware scheduled_streams
-- ----------------------------------------------------------------------------
alter table public.scheduled_streams
  add column if not exists host_timezone text default 'UTC';

-- A view that returns scheduled streams that are due to start NOW, computed
-- using each stream's host_timezone. The cron-auto-start-streams edge function
-- can use this instead of doing UTC-only math.
create or replace view public.streams_due_to_start as
select
  s.*,
  p.full_name as host_name,
  p.avatar_url as host_avatar_url,
  -- scheduled_local_ts = the wall-clock moment the stream should start, in
  -- the host's timezone, expressed as a UTC timestamptz for comparison with now().
  (s.scheduled_date::text || ' ' || s.scheduled_time || ':00 ' || coalesce(s.host_timezone, 'UTC'))::timestamptz as scheduled_local_ts
from public.scheduled_streams s
left join public.profiles p on p.id = s.host_id
where s.status = 'scheduled'
  and (s.scheduled_date::text || ' ' || s.scheduled_time || ':00 ' || coalesce(s.host_timezone, 'UTC'))::timestamptz <= now()
order by scheduled_local_ts asc;

grant select on public.streams_due_to_start to authenticated;

-- ----------------------------------------------------------------------------
-- 4. chat_backups table (StorageSettingsScreen "Back Up Now")
--    Tracks each backup's storage path, size, message count, and timestamp.
-- ----------------------------------------------------------------------------
create table if not exists public.chat_backups (
  id              uuid primary key default uuid_generate_v4(),
  user_id         uuid not null references auth.users(id) on delete cascade,
  storage_path    text not null,                  -- path in the 'backups' bucket
  file_name       text,
  file_size       bigint default 0,
  message_count   int default 0,
  conversation_count int default 0,
  backup_version  int default 1,
  status          text not null default 'completed'
    check (status in ('in_progress','completed','failed','restored')),
  created_at      timestamptz not null default now()
);
create index if not exists chat_backups_user_idx on public.chat_backups (user_id, created_at desc);

alter table public.chat_backups enable row level security;
drop policy if exists "chat_backups_owner_all" on public.chat_backups;
create policy "chat_backups_owner_all" on public.chat_backups
  for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

-- Add a 'backups' storage bucket (private, 500MB, encrypted-at-rest by default).
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('backups', 'backups', false, 524288000, array['application/octet-stream','application/json','application/zip','application/gzip'])
on conflict (id) do nothing;

drop policy if exists "backups_owner_read"   on storage.objects;
drop policy if exists "backups_owner_write"  on storage.objects;
drop policy if exists "backups_owner_delete" on storage.objects;
create policy "backups_owner_read"   on storage.objects for select to authenticated using (bucket_id = 'backups' and owner = auth.uid());
create policy "backups_owner_write"  on storage.objects for insert to authenticated with check (bucket_id = 'backups' and owner = auth.uid());
create policy "backups_owner_delete" on storage.objects for delete to authenticated using (bucket_id = 'backups' and owner = auth.uid());

-- ----------------------------------------------------------------------------
-- 5. blocked_contacts convenience RPC (block + unblock in one call)
-- ----------------------------------------------------------------------------
create or replace function public.set_contact_blocked(
  p_blocked_identifier text,
  p_blocked_user_id uuid default null,
  p_blocked boolean default true
)
returns void
language plpgsql
security definer set search_path = public
as $$
begin
  if p_blocked then
    insert into public.blocked_contacts (user_id, blocked_user_id, blocked_identifier)
    values (auth.uid(), p_blocked_user_id, p_blocked_identifier)
    on conflict (user_id, blocked_identifier) do nothing;
  else
    delete from public.blocked_contacts
    where user_id = auth.uid()
      and blocked_identifier = p_blocked_identifier
      and (p_blocked_user_id is null or blocked_user_id = p_blocked_user_id);
  end if;
end;
$$;

-- ============================================================================
-- Done.
-- ============================================================================

-- ============================================================================
-- 20260919_live_location_and_media_urls.sql
-- C7 (real live location) + permanent media URLs (re-sign companion).
-- Idempotent — safe to run twice.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. C7: live_location_shares — one row per (conversation, sharer).
-- The Android client runs a location foreground service that upserts fresh
-- coordinates every ~10 s; peers receive them via Supabase Realtime
-- postgres_changes (UPDATE events) filtered by conversation_id.
-- ----------------------------------------------------------------------------
create table if not exists public.live_location_shares (
  id uuid primary key default uuid_generate_v4(),
  conversation_id uuid not null references public.conversations(id) on delete cascade,
  sharer_id uuid not null references auth.users(id) on delete cascade,
  latitude double precision not null,
  longitude double precision not null,
  accuracy double precision,
  expires_at timestamptz not null,
  updated_at timestamptz not null default now(),
  unique (conversation_id, sharer_id)
);

alter table public.live_location_shares enable row level security;

-- Participants can read the shares of their conversations.
drop policy if exists "lls_select_participants" on public.live_location_shares;
create policy "lls_select_participants"
  on public.live_location_shares for select
  to authenticated
  using (
    exists (
      select 1 from public.conversations c
      where c.id = live_location_shares.conversation_id
        and (c.owner_id = auth.uid() or c.peer_id = auth.uid())
    )
  );

-- A sharer may create/update/delete ONLY their own row.
drop policy if exists "lls_insert_own" on public.live_location_shares;
create policy "lls_insert_own"
  on public.live_location_shares for insert
  to authenticated
  with check (
    sharer_id = auth.uid()
    and exists (
      select 1 from public.conversations c
      where c.id = live_location_shares.conversation_id
        and (c.owner_id = auth.uid() or c.peer_id = auth.uid())
    )
  );

drop policy if exists "lls_update_own" on public.live_location_shares;
create policy "lls_update_own"
  on public.live_location_shares for update
  to authenticated
  using (sharer_id = auth.uid())
  with check (sharer_id = auth.uid());

drop policy if exists "lls_delete_own" on public.live_location_shares;
create policy "lls_delete_own"
  on public.live_location_shares for delete
  to authenticated
  using (sharer_id = auth.uid());

-- Realtime: broadcast INSERT/UPDATE/DELETE to subscribed clients.
do $$
begin
  begin
    alter publication supabase_realtime add table public.live_location_shares;
  exception when duplicate_object then
    null; -- already in the publication
  end;
end $$;

-- ----------------------------------------------------------------------------
-- 2. Permanent media URLs: the client used to bake 7-day SIGNED urls into
-- messages.media_url. chat_media is a PUBLIC bucket now (20260918), so every
-- historical signed/authenticated url is rewritten to the permanent public
-- form — media renders forever on every device, no re-sign round-trip needed.
-- Idempotent: only touches rows still carrying a signed/authenticated url.
-- ----------------------------------------------------------------------------
update public.messages
set media_url = regexp_replace(
        replace(media_url, '/object/sign/chat_media/', '/object/public/chat_media/'),
        '\?token=[^&\s]+$', '')
where media_url like '%/object/sign/chat_media/%';

update public.messages
set media_url = regexp_replace(
        replace(media_url, '/object/authenticated/chat_media/', '/object/public/chat_media/'),
        '\?token=[^&\s]+$', '')
where media_url like '%/object/authenticated/chat_media/%';

-- Persist the storage bucket for messages that have a chat_media url but no
-- explicit bucket (legacy rows) — the client keeps media_bucket going forward
-- (media_path is a client-side Room column; the client derives the path from
-- the URL, so no server column is needed for it).
update public.messages
set media_bucket = 'chat_media'
where media_url like '%/object/%/chat_media/%'
  and (media_bucket is null or media_bucket = '');

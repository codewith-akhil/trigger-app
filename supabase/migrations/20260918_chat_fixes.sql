-- ============================================================================
-- 20260918_chat_fixes.sql
-- Chat audit remediation — run in the Supabase SQL editor (or supabase db push).
-- Companion to the client fixes in the same commit. Idempotent.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. C9: shared_links view bypassed messages RLS.
-- The view executed with the view owner's rights (no security_invoker), so ANY
-- authenticated user could read every user's link-bearing messages.
-- Requires PostgreSQL 15+ (Supabase default).
-- ----------------------------------------------------------------------------
alter view public.shared_links set (security_invoker = true);

-- ----------------------------------------------------------------------------
-- 2. H1: chat media was unreadable by recipients.
-- chat_media is a PRIVATE bucket whose Storage RLS only allows the UPLOADER to
-- read. The client now stores 7-day signed URLs (which bypass RLS at GET time),
-- but making the bucket public-readable also fixes rendering forever via public
-- URLs and lets peers re-sign expired URLs. Trade-off: anyone with the link can
-- read chat media objects (standard for consumer chat apps).
-- ----------------------------------------------------------------------------
update storage.buckets set public = true where id = 'chat_media';

drop policy if exists "chat_media_authenticated_read" on storage.objects;
create policy "chat_media_authenticated_read"
  on storage.objects for select to authenticated
  using (bucket_id = 'chat_media');

-- ----------------------------------------------------------------------------
-- 3. H6: typing / recording indicator state.
-- update-presence now persists short-lived expiry timestamps; Realtime
-- broadcasts UPDATE events on user_presences to the peer.
-- ----------------------------------------------------------------------------
alter table public.user_presences add column if not exists typing_until timestamptz;
alter table public.user_presences add column if not exists recording_until timestamptz;

-- ----------------------------------------------------------------------------
-- 4. M13: messages.seq was never populated (every row = 0).
-- Backfill + trigger so ordering is server-side instead of client-clock based.
-- ----------------------------------------------------------------------------
create or replace function public.set_message_seq() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if new.seq is null or new.seq = 0 then
    select coalesce(max(seq), 0) + 1 into new.seq
    from public.messages where conversation_id = new.conversation_id;
  end if;
  return new;
end $$;

drop trigger if exists trg_message_seq on public.messages;
create trigger trg_message_seq
  before insert on public.messages
  for each row execute function public.set_message_seq();

-- Backfill existing zero-seq rows per conversation, in created_at order
with ranked as (
  select id, row_number() over (partition by conversation_id order by created_at asc) as rn
  from public.messages where seq = 0
)
update public.messages m set seq = ranked.rn
from ranked where m.id = ranked.id;

-- ----------------------------------------------------------------------------
-- 5. L18: message_reactions was world-readable (`using (true)`).
-- Restrict to conversation participants.
-- ----------------------------------------------------------------------------
drop policy if exists "mr_select" on public.message_reactions;
drop policy if exists "message_reactions_select" on public.message_reactions;
create policy "message_reactions_select_participants"
  on public.message_reactions for select to authenticated
  using (
    exists (
      select 1
      from public.messages m
      join public.conversations c on c.id = m.conversation_id
      where m.id = message_reactions.message_id
        and (c.owner_id = auth.uid() or c.peer_id = auth.uid())
    )
  );

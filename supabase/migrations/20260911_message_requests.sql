-- Migration: 20260911_message_requests.sql
-- Message requests + contacts + conversations request_status

alter table public.conversations add column if not exists request_status text not null default 'accepted' check (request_status in ('pending','accepted','blocked'));
alter table public.conversations add column if not exists is_contact boolean not null default false;

create table if not exists public.message_requests (
  id uuid primary key default uuid_generate_v4(),
  sender_id uuid not null references auth.users(id) on delete cascade,
  receiver_id uuid not null references auth.users(id) on delete cascade,
  sender_name text not null,
  sender_username text,
  sender_avatar_url text,
  initial_message text not null,
  status text not null default 'pending' check (status in ('pending','accepted','blocked')),
  conversation_id uuid references public.conversations(id) on delete set null,
  created_at timestamptz not null default now(),
  responded_at timestamptz,
  unique(sender_id, receiver_id)
);
create index if not exists msg_req_receiver_idx on public.message_requests (receiver_id, status);
create index if not exists msg_req_sender_idx on public.message_requests (sender_id, status);
alter table public.message_requests enable row level security;
drop policy if exists "msg_req_select" on public.message_requests;
create policy "msg_req_select" on public.message_requests for select to authenticated using (sender_id = auth.uid() or receiver_id = auth.uid());
drop policy if exists "msg_req_insert" on public.message_requests;
create policy "msg_req_insert" on public.message_requests for insert to authenticated with check (sender_id = auth.uid());
drop policy if exists "msg_req_update" on public.message_requests;
create policy "msg_req_update" on public.message_requests for update to authenticated using (receiver_id = auth.uid());

create table if not exists public.contacts (
  id uuid primary key default uuid_generate_v4(),
  user_id uuid not null references auth.users(id) on delete cascade,
  contact_user_id uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  unique(user_id, contact_user_id)
);
create index if not exists contacts_user_idx on public.contacts (user_id);
alter table public.contacts enable row level security;
drop policy if exists "contacts_owner_all" on public.contacts;
create policy "contacts_owner_all" on public.contacts for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

alter publication supabase_realtime add table public.message_requests;
alter publication supabase_realtime add table public.contacts;

-- Migration: 20260914_chat_production.sql
-- Production chat features: presence expiration, idempotency, message ordering, pin

-- 1. Presence expiration: auto-set offline after 2 minutes of no heartbeat
create or replace function public.expire_stale_presence()
returns integer
language plpgsql
security definer set search_path = public
as $$
declare v_expired integer;
begin
  update public.user_presences
    set is_online = false, updated_at = now()
    where is_online = true
      and updated_at < now() - interval '2 minutes';
  get diagnostics v_expired = row_count;
  return v_expired;
end;
$$;

-- Schedule presence expiration every 1 minute
do $$ begin
  perform cron.unschedule('trigger-expire-presence');
exception when others then null;
end $$;
select cron.schedule('trigger-expire-presence', '* * * * *',
  $$ select public.expire_stale_presence() as expired_count; $$);

-- 2. Idempotency: add idempotency_key column to messages
alter table public.messages add column if not exists idempotency_key text;
create index if not exists messages_idempotency_idx on public.messages (idempotency_key) where idempotency_key is not null;

-- 3. Message ordering: add server-side sequence number
alter table public.messages add column if not exists seq bigint not null default 0;
create index if not exists messages_conv_seq_idx on public.messages (conversation_id, seq desc);

-- 4. Pin message: add is_pinned column
alter table public.messages add column if not exists is_pinned boolean not null default false;
create index if not exists messages_pinned_idx on public.messages (conversation_id) where is_pinned = true;

-- 5. Edit tracking: add edited_at column
alter table public.messages add column if not exists edited_at timestamptz;

-- 6. Add report_reason to support_tickets (for user reports)
alter table public.support_tickets add column if not exists reported_user_id uuid references auth.users(id) on delete cascade;

-- 7. Add is_archived to conversations (column exists but let's ensure)
alter table public.conversations add column if not exists is_archived boolean not null default false;

-- 8. Add links extraction: create a view for links shared in a conversation
create or replace view public.shared_links as
select
  m.id as message_id,
  m.conversation_id,
  m.sender_id,
  m.text,
  m.created_at
from public.messages m
where m.type = 'TEXT'
  and m.text ~* 'https?://'
  and m.is_deleted_for_everyone = false
order by m.created_at desc;

grant select on public.shared_links to authenticated;

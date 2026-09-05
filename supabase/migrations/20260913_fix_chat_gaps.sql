-- Migration: 20260913_fix_chat_gaps.sql
-- Create increment_unread_count RPC (was called but never defined)

create or replace function public.increment_unread_count(
  p_conversation_id uuid,
  p_user_id uuid
)
returns void
language plpgsql
security definer set search_path = public
as $$
begin
  update public.conversations
    set unread_count = unread_count + 1
    where id = p_conversation_id
      and (owner_id = p_user_id or peer_id = p_user_id);
end;
$$;

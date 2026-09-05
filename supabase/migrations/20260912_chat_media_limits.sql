-- Migration: 20260912_chat_media_limits.sql
-- Update storage bucket limits for real chat media + add increment_unread_count RPC

-- Update chat_media bucket to 250MB (videos)
update storage.buckets set file_size_limit = 262144000 where id = 'chat_media';
-- Update voice_notes to 55MB
update storage.buckets set file_size_limit = 57671680 where id = 'voice_notes';
-- Update documents to 55MB
update storage.buckets set file_size_limit = 57671680 where id = 'documents';

-- RPC to increment unread_count for the receiver's conversation
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

-- RPC to mark messages as read (called when user opens a conversation)
create or replace function public.mark_messages_read(
  p_conversation_id uuid,
  p_reader_id uuid
)
returns integer
language plpgsql
security definer set search_path = public
as $$
declare
  v_updated integer;
begin
  -- Mark all messages NOT sent by the reader as read
  update public.messages
    set read_at = now(), status = 'READ'
    where conversation_id = p_conversation_id
      and sender_id <> p_reader_id
      and read_at is null;
  get diagnostics v_updated = row_count;

  -- Reset unread count for the reader's conversation
  update public.conversations
    set unread_count = 0, last_read_at = now()
    where id = p_conversation_id
      and (owner_id = p_reader_id or peer_id = p_reader_id);

  return v_updated;
end;
$$;

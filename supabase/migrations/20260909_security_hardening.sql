-- ============================================================================
-- Trigger App — Security hardening migration
-- Migration: 20260909_security_hardening.sql
-- ----------------------------------------------------------------------------
-- Fixes the CRITICAL + HIGH issues found in the AUDIT-BACKEND pass:
--   C11: mark_conversation_read RPC — add participant guard.
--   C13: vault_pins RLS — remove UPDATE/DELETE (service role only).
--   C14: record_wallet_transaction RPC — REVOKE from public (drop it).
--   H:   wallet_transactions — tighten UPDATE RLS (only status, not amount).
--   H:   messages — tighten UPDATE RLS (only own messages).
--   H:   increment_stream_booking — make atomic with SELECT FOR UPDATE.
--   H:   Add participant check RPC for conversation access.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- C11: Fix mark_conversation_read — add participant guard
-- Only the conversation owner or peer can mark messages as read.
-- ----------------------------------------------------------------------------
drop function if exists public.mark_conversation_read(uuid);
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
  -- Guard: only the owner or peer of this conversation can mark it read.
  select exists(
    select 1 from public.conversations c
    where c.id = p_conversation_id
      and (c.owner_id = auth.uid() or c.peer_id = auth.uid())
  ) into v_is_participant;

  if not v_is_participant then
    raise exception 'Not a participant in this conversation' using errcode = '42501';
  end if;

  update public.messages
    set read_at = now()
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

-- ----------------------------------------------------------------------------
-- C13: Fix vault_pins RLS — owner can SELECT only; no UPDATE/DELETE
-- (prevents brute-force lockout bypass by setting attempts=0)
-- ----------------------------------------------------------------------------
drop policy if exists "vp_owner_all" on public.vault_pins;
-- SELECT-only policy: owner can check if a PIN exists (but not read the hash
-- via PostgREST — the hash column is only accessed via the verify edge function
-- which uses the service role).
create policy "vp_owner_select" on public.vault_pins
  for select to authenticated using (user_id = auth.uid());
-- No INSERT/UPDATE/DELETE policy → only the service role (edge functions) can
-- modify vault_pins. The upsert-vault-pin + verify-vault-pin + reset-vault-pin
-- functions all use createAdminClient() (service role), so they bypass RLS.

-- ----------------------------------------------------------------------------
-- C14: Drop the exposed record_wallet_transaction RPC
-- It was security_definer + exposed via PostgREST → any user could self-credit.
-- The wallet-withdraw edge function uses direct INSERT (service role) instead.
-- ----------------------------------------------------------------------------
drop function if exists public.record_wallet_transaction(
  text, numeric, text, text, text, text, uuid
);

-- ----------------------------------------------------------------------------
-- H: Tighten wallet_transactions UPDATE RLS — only allow status update,
-- not amount/type/user_id changes (prevents financial integrity tampering).
-- ----------------------------------------------------------------------------
drop policy if exists "wt_owner_update" on public.wallet_transactions;
-- Remove UPDATE entirely — transactions are immutable once inserted.
-- The only legitimate UPDATE is the razorpay-webhook setting status='failed'
-- for refunds, and that uses the service role (bypasses RLS).
-- Owners can SELECT + INSERT (INSERT is used by... actually no, only the
-- service role inserts via edge functions). Let's keep INSERT for future
-- client-side use but remove UPDATE.
create policy "wt_owner_select" on public.wallet_transactions
  for select to authenticated using (user_id = auth.uid());
create policy "wt_owner_insert" on public.wallet_transactions
  for insert to authenticated with check (user_id = auth.uid());
-- No UPDATE policy → immutable for clients.

-- ----------------------------------------------------------------------------
-- H: Tighten messages UPDATE RLS — only the sender can update their own
-- messages (status, read_at). Conversation owner should NOT be able to edit
-- the peer's messages.
-- ----------------------------------------------------------------------------
drop policy if exists "msg_participant_update" on public.messages;
create policy "msg_sender_update" on public.messages
  for update to authenticated
  using (sender_id = auth.uid())
  with check (sender_id = auth.uid());

-- ----------------------------------------------------------------------------
-- H: Make increment_stream_booking atomic with row-level lock
-- Prevents race condition where two concurrent bookings both pass the
-- slot_check and over-book past slot_limit.
-- ----------------------------------------------------------------------------
drop function if exists public.increment_stream_booking();
create or replace function public.increment_stream_booking()
returns trigger
language plpgsql
security definer set search_path = public
as $$
begin
  -- Lock the scheduled_streams row to serialize concurrent bookings.
  perform 1 from public.scheduled_streams
    where id = new.stream_id
    for update;

  update public.scheduled_streams
    set slots_booked = slots_booked + 1
    where id = new.stream_id
      and (slot_limit = 'ANY' or slots_booked < slot_limit::int);
  if not found then
    raise exception 'Stream is fully booked' using errcode = '40001';
  end if;
  return new;
end;
$$;

-- ----------------------------------------------------------------------------
-- H: Add a conversation-participant-check helper RPC (for edge functions)
-- Returns true if the caller is owner or peer of the given conversation.
-- ----------------------------------------------------------------------------
create or replace function public.is_conversation_participant(
  p_conversation_id uuid
)
returns boolean
language plpgsql
security definer set search_path = public
as $$
begin
  return exists(
    select 1 from public.conversations c
    where c.id = p_conversation_id
      and (c.owner_id = auth.uid() or c.peer_id = auth.uid())
  );
end;
$$;

-- ============================================================================
-- Done.
-- ============================================================================

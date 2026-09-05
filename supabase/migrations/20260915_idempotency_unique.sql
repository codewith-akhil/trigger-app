-- Migration: 20260915_idempotency_unique.sql
-- Add UNIQUE constraint on idempotency_key for server-side duplicate prevention.
-- Partial index (only when idempotency_key is not null) so messages without
-- a key are not affected.
create unique index if not exists messages_idempotency_key_unique
  on public.messages (idempotency_key)
  where idempotency_key is not null;

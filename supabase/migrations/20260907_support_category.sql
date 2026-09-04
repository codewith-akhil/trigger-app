-- ============================================================================
-- Trigger App — Add category column to support_tickets
-- Migration: 20260907_support_category.sql
-- ----------------------------------------------------------------------------
-- The create-support-ticket edge function accepts a `category` field but the
-- table had no column for it (the category was only sent in the email subject
-- + Resend tag). Adding it so support agents can filter tickets by category
-- in the dashboard.
-- ============================================================================

alter table public.support_tickets
  add column if not exists category text not null default 'general'
    check (category in ('general','bug','billing','stream','account'));

create index if not exists support_tickets_category_idx
  on public.support_tickets (category)
  where status = 'open';

-- ============================================================================
-- Done.
-- ============================================================================

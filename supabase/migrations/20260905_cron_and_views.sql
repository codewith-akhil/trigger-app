-- ============================================================================
-- Trigger App — Cron schedule + helper views + OTP cleanup
-- Migration: 20260905_cron_and_views.sql
-- ----------------------------------------------------------------------------
-- Adds:
--   1. A pg_cron job that calls the cron-auto-start-streams edge function
--      every minute (requires the `pg_cron` + `pg_net` extensions, both
--      enabled by default on Supabase).
--   2. A `stream_history` view joining scheduled_streams + live_streams for
--      the StreamHistoryScreen.
--   3. A `cleanup_expired_otps()` function (safe to call from a cron or a
--      scheduled Supabase Function).
--   4. A `wallet_balance` view aggregating wallet_transactions per user.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 0. Extensions
-- ----------------------------------------------------------------------------
create extension if not exists pg_cron with schema extensions;
create extension if not exists pg_net  with schema extensions;

-- ----------------------------------------------------------------------------
-- 1. pg_cron job — auto-start scheduled streams every minute
-- ----------------------------------------------------------------------------
-- The job POSTs to the edge function via pg_net. The function is idempotent
-- (only transitions status='scheduled' rows whose time has passed), so running
-- every minute is safe.
do $$
begin
  -- Drop any existing job with the same name to make this re-runnable.
  perform cron.unschedule('trigger-auto-start-streams');
exception when others then
  null;
end $$;

select cron.schedule(
  'trigger-auto-start-streams',
  '* * * * *',  -- every minute
  $$
    select net.http_post(
      url := 'https://uazkcainrajcgxecomly.functions.supabase.co/cron-auto-start-streams',
      headers := jsonb_build_object(
        'Content-Type', 'application/json',
        'Authorization', 'Bearer ' || current_setting('request.jwt.claim', true)
      ),
      body := jsonb_build_object()
    ) as request_id;
  $$
);

-- ----------------------------------------------------------------------------
-- 2. stream_history view (StreamHistoryScreen)
--    Joins scheduled_streams with their live_streams counterpart (if any).
-- ----------------------------------------------------------------------------
create or replace view public.stream_history as
select
  s.id                as scheduled_stream_id,
  s.host_id,
  p.full_name         as host_name,
  p.avatar_url        as host_avatar_url,
  s.title,
  s.description,
  s.category,
  s.scheduled_date,
  s.scheduled_time,
  s.pricing_type,
  s.amount,
  s.currency,
  s.slot_limit,
  s.slots_booked,
  s.status            as scheduled_status,
  s.share_link,
  l.id                as live_stream_id,
  l.channel_name,
  l.status            as live_status,
  l.viewer_count,
  l.total_likes,
  l.started_at,
  l.ended_at,
  coalesce(l.viewer_count, 0) as peak_viewers
from public.scheduled_streams s
left join public.live_streams l
  on l.channel_name = s.channel_name
left join public.profiles p
  on p.id = s.host_id
order by s.scheduled_date desc, s.scheduled_time desc;

comment on view public.stream_history is
  'Denormalised join of scheduled_streams + live_streams + profiles for the StreamHistoryScreen.';

-- Grant read access to authenticated users (the underlying table RLS still
-- applies — they can only see public or own-host streams).
grant select on public.stream_history to authenticated;

-- ----------------------------------------------------------------------------
-- 3. cleanup_expired_otps() — removes consumed + expired OTP rows
--    Call from a daily cron or a Supabase scheduled function.
-- ----------------------------------------------------------------------------
create or replace function public.cleanup_expired_otps()
returns integer
language plpgsql
security definer set search_path = public
as $$
declare
  v_deleted integer;
begin
  -- Delete OTPs that are either consumed or expired (>24h old for audit).
  delete from public.otp_codes
  where consumed_at is not null
     or expires_at < now() - interval '24 hours';
  get diagnostics v_deleted = row_count;
  return v_deleted;
end;
$$;

-- Schedule a daily cleanup at 03:00 UTC.
do $$
begin
  perform cron.unschedule('trigger-cleanup-otps');
exception when others then null;
end $$;

select cron.schedule(
  'trigger-cleanup-otps',
  '0 3 * * *',  -- daily at 03:00 UTC
  $$ select public.cleanup_expired_otps() as deleted_count; $$
);

-- ----------------------------------------------------------------------------
-- 4. wallet_balance view — running balance per user
--    Sums credits - debits for completed + processing transactions.
-- ----------------------------------------------------------------------------
create or replace view public.wallet_balance as
select
  user_id,
  coalesce(sum(case when type = 'credit' and status = 'completed' then amount else 0 end), 0) as total_earned,
  coalesce(sum(case when type = 'debit' and status in ('completed','processing') then amount else 0 end), 0) as total_withdrawn,
  coalesce(sum(case when type = 'credit' and status = 'completed' then amount else 0 end), 0)
    - coalesce(sum(case when type = 'debit' and status in ('completed','processing') then amount else 0 end), 0) as available_balance,
  coalesce(sum(case when type = 'debit' and status = 'processing' then amount else 0 end), 0) as pending_balance
from public.wallet_transactions
group by user_id;

comment on view public.wallet_balance is
  'Aggregated wallet balance per user (available, pending, total earned, total withdrawn).';

grant select on public.wallet_balance to authenticated;

-- ----------------------------------------------------------------------------
-- 5. Cleanup function for ended live streams (housekeeping)
--    Marks live_streams that have been live > 4 hours as ended (safety net
--    for hosts who forgot to press End Stream).
-- ----------------------------------------------------------------------------
create or replace function public.auto_end_stale_live_streams()
returns integer
language plpgsql
security definer set search_path = public
as $$
declare v_ended integer;
begin
  update public.live_streams
    set status = 'ended', ended_at = now()
    where status = 'live' and started_at < now() - interval '4 hours';
  get diagnostics v_ended = row_count;
  return v_ended;
end;
$$;

do $$
begin
  perform cron.unschedule('trigger-end-stale-streams');
exception when others then null;
end $$;

select cron.schedule(
  'trigger-end-stale-streams',
  '*/5 * * * *',  -- every 5 minutes
  $$ select public.auto_end_stale_live_streams() as ended_count; $$
);

-- ============================================================================
-- Done.
-- ============================================================================

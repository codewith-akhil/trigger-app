-- ============================================================================
-- 20260927_cron_secret_out_of_git.sql
-- ============================================================================
-- SECURITY: the shared cron secret was previously INLINED into a committed
-- migration (20260921 M5) — a live credential in source control. This
-- migration moves the secret into a deny-all table whose VALUE is inserted
-- out-of-band on the live database only (never in git), rotates it, and
-- re-points all three cron jobs at the table.
--
-- The live secret must ALSO be rotated in the edge-function environment:
--   supabase secrets set CRON_SECRET=<new-value>
-- (both cron-auto-start-streams and update-fx-rates compare against it.)
--
-- Idempotent: guarded drops/creates; cron jobs re-declared replay-safe.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- §1  Private secret store (deny-all RLS; service role manages contents)
-- ---------------------------------------------------------------------------
create table if not exists public.app_secrets (
  key   text primary key,
  value text not null,
  updated_at timestamptz not null default now()
);

alter table public.app_secrets enable row level security;

-- No policies at all → only the service/postgres roles (cron runs as
-- postgres) can read it; anon/authenticated get zero rows and no EXECUTE
-- paths around it.
revoke all on public.app_secrets from anon, authenticated;

-- ---------------------------------------------------------------------------
-- §2  Re-declare the cron jobs reading the secret from the store
-- ---------------------------------------------------------------------------
select cron.unschedule('trigger-auto-start-streams')
where exists (select 1 from cron.job where jobname = 'trigger-auto-start-streams');
select cron.schedule(
  'trigger-auto-start-streams', '* * * * *',
  $cmd$ select net.http_post(
    url := 'https://uazkcainrajcgxecomly.functions.supabase.co/cron-auto-start-streams',
    headers := jsonb_build_object('Content-Type', 'application/json',
      'x-cron-secret', (select value from public.app_secrets where key = 'cron_secret')),
    body := jsonb_build_object()
  ) as request_id $cmd$
);

select cron.unschedule('trigger-update-fx-rates-2am')
where exists (select 1 from cron.job where jobname = 'trigger-update-fx-rates-2am');
select cron.schedule(
  'trigger-update-fx-rates-2am', '0 2 * * *',
  $cmd$ select net.http_post(
    url := 'https://uazkcainrajcgxecomly.functions.supabase.co/update-fx-rates',
    headers := jsonb_build_object('Content-Type', 'application/json',
      'x-cron-secret', (select value from public.app_secrets where key = 'cron_secret')),
    body := jsonb_build_object()
  ) as request_id $cmd$
);

select cron.unschedule('trigger-update-fx-rates-2pm')
where exists (select 1 from cron.job where jobname = 'trigger-update-fx-rates-2pm');
select cron.schedule(
  'trigger-update-fx-rates-2pm', '0 14 * * *',
  $cmd$ select net.http_post(
    url := 'https://uazkcainrajcgxecomly.functions.supabase.co/update-fx-rates',
    headers := jsonb_build_object('Content-Type', 'application/json',
      'x-cron-secret', (select value from public.app_secrets where key = 'cron_secret')),
    body := jsonb_build_object()
  ) as request_id $cmd$
);

-- ============================================================================
-- 20261001_media_archive_compliance.sql
-- Phase 6: legal-retention archive (owner's explicit compliance decision).
--
-- WHAT THIS DOES
--   1. public.media_archive — append-only-style LEDGER of every chat media
--      message (INCLUDING view-once media). Populated by the send-message
--      edge function at send time; purged daily by the purge-expired-media
--      edge function. Rows are NEVER deleted (the ledger must prove deletion
--      happened: status 'retained' -> 'purged' + purged_at).
--   2. PRIVATE vault bucket `media_vault` — server-side COPY (never move) of
--      every chat media object at archive time, path
--      '<message_id>/<filename>'. NO storage.objects policies are created for
--      it, so anon/authenticated have zero access — service role only.
--   3. media_archive RLS: ENABLED with ZERO policies — service_role (edge
--      functions + purge cron) bypasses RLS; anon/authenticated get nothing
--      (no policies + explicit privilege revokes). End users can NEVER read
--      the archive. The owner alone reaches it via the admin-evidence edge
--      function (service-role authenticated).
--   4. Retention: purge_at = received_at + 14 days (hard purge). Daily
--      pg_cron job (04:00 UTC, documented choice) fires the
--      purge-expired-media function via pg_net with the service-role key
--      read from public.app_secrets (same RLS-locked table cron_secret uses).
--      status 'legal_hold' is deliberately never purged (owner sets it
--      manually via service role when authorities require preservation).
--
-- APPLIES AS: the DDL below is idempotent. The service-role key for the cron
--   job is NEVER committed — at apply time it is injected via a psql variable
--   (:service_role_key) / in-memory substitution; see worklog Task 6.
-- DEPLOY NOTE: this migration is applied directly (psql session pooler) by
--   Phase 6; `supabase db push` will treat it as already applied only if the
--   migration table is updated — the file is the durable record.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Ledger table
-- ----------------------------------------------------------------------------
create table if not exists public.media_archive (
  id             uuid primary key default gen_random_uuid(),
  message_id     text not null unique,          -- messages.id (uuid as text) — idempotency key
  conversation_id text not null,
  sender_id      text not null,
  receiver_id    text,                          -- nullable: groups later
  media_kind     text not null check (media_kind in ('image','video','voice','audio','file')),
  source_bucket  text not null,                 -- chat_media / voice_notes / documents / backups
  source_path    text not null,                 -- original object path (bare path form)
  vault_path     text,                          -- '<message_id>/<filename>' in media_vault
  sha256         text,                          -- nullable: not computed on the send path
  byte_size      bigint,
  mime           text,
  is_view_once   boolean not null default false,
  received_at    timestamptz not null default now(),
  purge_at       timestamptz not null default (now() + interval '14 days'),
  status         text not null default 'retained' check (status in ('retained','purged','legal_hold')),
  purged_at      timestamptz,
  constraint media_archive_purge_after_received check (purge_at >= received_at)
);

-- RLS: enabled, ZERO policies (verified by Task 6). service_role bypasses
-- RLS; anon/authenticated have no policy and (below) no grants at all.
alter table public.media_archive enable row level security;

-- Supabase default privileges would hand anon/authenticated ALL table
-- privileges on new postgres tables — revoke them so the archive is
-- unreachable through the Data API. Deliberately NOT `revoke ... from
-- PUBLIC`: that would also strip service_role (learned in
-- 20260921_deep_audit_hardening.sql).
revoke all on public.media_archive from anon;
revoke all on public.media_archive from authenticated;
grant select, insert, update, delete on public.media_archive to service_role;

-- (status, purge_at) drives the daily purge sweep; the rest serve the
-- admin-evidence filters.
create index if not exists media_archive_status_purge_idx on public.media_archive (status, purge_at);
create index if not exists media_archive_conversation_idx on public.media_archive (conversation_id);
create index if not exists media_archive_sender_idx on public.media_archive (sender_id);
create index if not exists media_archive_receiver_idx on public.media_archive (receiver_id);

-- ----------------------------------------------------------------------------
-- 2. Private vault bucket (NO object policies → service role only)
-- ----------------------------------------------------------------------------
insert into storage.buckets (id, name, public)
values ('media_vault', 'media_vault', false)
on conflict (id) do nothing;

-- ----------------------------------------------------------------------------
-- 3. Daily purge schedule — pg_cron (already enabled, v1.6.4) -> pg_net
--    POST to the purge-expired-media edge function with the service-role key.
--    04:00 UTC chosen (documented in worklog Task 6): after the 03:00
--    trigger-cleanup-otps job so the two daily sweeps never overlap.
--    The function self-validates the Authorization header (service role key
--    only, timing-safe). Until that function is deployed the job records
--    404s in net._http_response — harmless, becomes live on deploy.
-- ----------------------------------------------------------------------------
select cron.schedule(
  'trigger-purge-expired-media',
  '0 4 * * *',
  $$
  select net.http_post(
    url := 'https://uazkcainrajcgxecomly.functions.supabase.co/purge-expired-media',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'Authorization', 'Bearer ' || (select value from public.app_secrets where key = 'service_role_key')
    ),
    body := jsonb_build_object()
  ) as request_id
  $$
);

-- ----------------------------------------------------------------------------
-- 4. Service-role key for the cron job — VALUE INJECTED AT APPLY TIME.
--    app_secrets has RLS enabled with ZERO policies and grants only to
--    postgres + service_role, exactly like cron_secret (jobs 16/17/18 read
--    it the same way). Never commit the real key.
-- ----------------------------------------------------------------------------
insert into public.app_secrets (key, value, updated_at)
values ('service_role_key', :'service_role_key', now())
on conflict (key) do update set value = excluded.value, updated_at = now();

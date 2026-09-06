-- ============================================================================
-- Trigger App — Storage policy fix: avatars UPDATE + DELETE policies
-- Migration: 20260917_storage_policy_fix.sql
-- ----------------------------------------------------------------------------
-- SYMPTOM (diagnosed 2026-09-06 via live API probes against the project):
--   - INSERT into avatars bucket with a valid user JWT          -> 200 OK
--   - Same request with x-upsert: true (the app's avatar upload) -> 403
--     "new row violates row-level security policy"
--   - PUT (update) of an existing avatar object                  -> 403
--   - DELETE of an own avatar object                             -> 403
--
-- ROOT CAUSE:
--   The deployed storage.objects RLS state has a working INSERT policy but the
--   UPDATE policy is missing or non-functional, and there is no DELETE policy.
--   The Android client sent x-upsert: true on avatar uploads; Supabase Storage
--   evaluates the UPDATE policy for upsert requests -> 403.
--
--   Client-side mitigation shipped in the same change: avatar uploads no longer
--   set x-upsert (every upload already uses a unique {uid}/{timestamp}.{ext}
--   path). This migration restores correct server-side policies so
--   update/delete also work, matching 20260904_full_app_schema.sql's intent.
--
-- WHY owner_id: newer Supabase Storage writes owner_id (text = auth uid) and
--   the legacy owner (uuid) column is deprecated on some projects. Covering
--   both columns makes the policies robust regardless of which one the
--   platform populates.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. UPDATE policy — explicit USING + WITH CHECK (bucket-scoped, owner-scoped)
-- ----------------------------------------------------------------------------
drop policy if exists "avatars_owner_update" on storage.objects;
create policy "avatars_owner_update" on storage.objects
  for update to authenticated
  using (
    bucket_id = 'avatars'
    and (owner = auth.uid() or owner_id = auth.uid()::text)
  )
  with check (
    bucket_id = 'avatars'
    and (owner = auth.uid() or owner_id = auth.uid()::text)
  );

-- ----------------------------------------------------------------------------
-- 2. DELETE policy — lets users clean up their own old avatar files
-- ----------------------------------------------------------------------------
drop policy if exists "avatars_owner_delete" on storage.objects;
create policy "avatars_owner_delete" on storage.objects
  for delete to authenticated
  using (
    bucket_id = 'avatars'
    and (owner = auth.uid() or owner_id = auth.uid()::text)
  );

-- ----------------------------------------------------------------------------
-- 3. INSERT policy — recreate unchanged in behavior (owner-scoped only), plus
--    owner_id compatibility. Do NOT add stricter path rules here without
--    auditing every client upload path first.
-- ----------------------------------------------------------------------------
drop policy if exists "avatars_owner_write" on storage.objects;
create policy "avatars_owner_write" on storage.objects
  for insert to authenticated
  with check (
    bucket_id = 'avatars'
    and (owner = auth.uid() or owner_id = auth.uid()::text)
  );

-- ----------------------------------------------------------------------------
-- OPTIONAL HARDENING (not applied by default — audit client upload paths first):
-- restrict avatar INSERTs to the caller's own {uid}/ folder:
--   with check (
--     bucket_id = 'avatars'
--     and name like auth.uid()::text || '/%'
--   )
-- Currently the ONLY client-side upload (ProfileScreen.saveAvatar) already
-- uses "{uid}/{timestamp}.{ext}" paths, so the strict version would work for
-- the app today; it is left optional to avoid breaking any dashboard/manual
-- uploads to the bucket root.
-- ============================================================================

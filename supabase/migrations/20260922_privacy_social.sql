-- ============================================================================
-- Migration 20260922: Privacy + social — visibility token widening and the
-- user_notifications inbox table.
--
-- Sections:
--   1. user_settings visibility CHECKs — widen last_seen /
--      profile_photo_visibility / about_visibility with the social tokens
--      'followers' | 'following' (legacy 'contacts' stays valid)
--   2. user_notifications table (follow / message_request_accepted inbox)
--      + RLS + index + realtime publication
--
-- All statements are idempotent.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. user_settings visibility CHECK swaps
--    The original inline CHECKs only allowed ('everyone','contacts','nobody');
--    the Privacy page now writes the social tokens. Old clients keep working
--    because 'contacts' remains in the list (read paths treat it as
--    'followers').
-- ---------------------------------------------------------------------------
ALTER TABLE public.user_settings
  DROP CONSTRAINT IF EXISTS user_settings_last_seen_check;
ALTER TABLE public.user_settings
  ADD CONSTRAINT user_settings_last_seen_check
  CHECK (last_seen IN ('everyone', 'contacts', 'followers', 'following', 'nobody'));

ALTER TABLE public.user_settings
  DROP CONSTRAINT IF EXISTS user_settings_profile_photo_visibility_check;
ALTER TABLE public.user_settings
  ADD CONSTRAINT user_settings_profile_photo_visibility_check
  CHECK (profile_photo_visibility IN ('everyone', 'contacts', 'followers', 'following', 'nobody'));

ALTER TABLE public.user_settings
  DROP CONSTRAINT IF EXISTS user_settings_about_visibility_check;
ALTER TABLE public.user_settings
  ADD CONSTRAINT user_settings_about_visibility_check
  CHECK (about_visibility IN ('everyone', 'contacts', 'followers', 'following', 'nobody'));

-- ---------------------------------------------------------------------------
-- 2. user_notifications — DB-backed notification inbox
--    Rows are INSERTed ONLY by service-role edge functions (toggle-follow-user,
--    respond-message-request) — hence NO insert policy. Clients read and
--    mark-read their own rows.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.user_notifications (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  actor_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  type text NOT NULL CHECK (type IN ('follow', 'message_request_accepted')),
  is_read boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE public.user_notifications ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "un_select_own" ON public.user_notifications;
CREATE POLICY "un_select_own" ON public.user_notifications
  FOR SELECT TO authenticated
  USING (user_id = auth.uid());

DROP POLICY IF EXISTS "un_update_own" ON public.user_notifications;
CREATE POLICY "un_update_own" ON public.user_notifications
  FOR UPDATE TO authenticated
  USING (user_id = auth.uid())
  WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS "un_delete_own" ON public.user_notifications;
CREATE POLICY "un_delete_own" ON public.user_notifications
  FOR DELETE TO authenticated
  USING (user_id = auth.uid());

CREATE INDEX IF NOT EXISTS user_notifications_user_created_idx
  ON public.user_notifications(user_id, created_at DESC);

-- Realtime: broadcast changes to subscribed clients (idempotent).
DO $$
BEGIN
  BEGIN
    ALTER PUBLICATION supabase_realtime ADD TABLE public.user_notifications;
  EXCEPTION WHEN duplicate_object THEN
    NULL; -- already in the publication
  END;
END $$;

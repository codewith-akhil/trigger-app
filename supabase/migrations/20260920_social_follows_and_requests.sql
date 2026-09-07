-- ============================================================================
-- Migration 20260920: Social follows + message-request hardening + presence
-- gating + call history integrity.
--
-- Sections:
--   1. follows table (Instagram-style social graph) + RLS + indexes
--   2. conversations.request_status — constrain + 'declined' value
--   3. message_requests.status — allow 'declined'
--   4. Enforce one conversation row per (owner, peer) pair
--   5. user_presences SELECT gating — presence visible ONLY within an
--      ACCEPTED conversation (message request accepted), or self
--   6. call_sessions.receiver_id text → uuid + FK + index (call history)
--   7. Cleanup of stale call rows that block the type change
--
-- All statements are idempotent.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. follows — social graph (Instagram model: follow ≠ chat permission)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.follows (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  follower_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  following_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT follows_no_self CHECK (follower_id <> following_id),
  CONSTRAINT follows_pair_unique UNIQUE (follower_id, following_id)
);

ALTER TABLE public.follows ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS follows_select_authenticated ON public.follows;
CREATE POLICY follows_select_authenticated ON public.follows
  FOR SELECT TO authenticated
  USING (true);

DROP POLICY IF EXISTS follows_insert_self ON public.follows;
CREATE POLICY follows_insert_self ON public.follows
  FOR INSERT TO authenticated
  WITH CHECK (auth.uid() = follower_id AND follower_id <> following_id);

DROP POLICY IF EXISTS follows_delete_self ON public.follows;
CREATE POLICY follows_delete_self ON public.follows
  FOR DELETE TO authenticated
  USING (auth.uid() = follower_id);

CREATE INDEX IF NOT EXISTS follows_follower_idx
  ON public.follows(follower_id, created_at DESC);
CREATE INDEX IF NOT EXISTS follows_following_idx
  ON public.follows(following_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 2. conversations.request_status — constrain to known states
--    (legacy rows default to 'accepted'; column default already 'accepted')
-- ---------------------------------------------------------------------------
UPDATE public.conversations SET request_status = 'accepted' WHERE request_status IS NULL;

ALTER TABLE public.conversations DROP CONSTRAINT IF EXISTS conversations_request_status_check;
ALTER TABLE public.conversations ADD CONSTRAINT conversations_request_status_check
  CHECK (request_status IN ('pending', 'accepted', 'blocked', 'declined'));

-- ---------------------------------------------------------------------------
-- 3. message_requests.status — allow 'declined' (Instagram "Delete" action)
-- ---------------------------------------------------------------------------
ALTER TABLE public.message_requests DROP CONSTRAINT IF EXISTS message_requests_status_check;
ALTER TABLE public.message_requests ADD CONSTRAINT message_requests_status_check
  CHECK (status IN ('pending', 'accepted', 'blocked', 'declined'));

-- ---------------------------------------------------------------------------
-- 4. One conversation row per (owner, peer) — prevents duplicate rows for the
--    same pair racing in from resolveOrCreateConversation / send-message.
--    NOTE: (owner=A, peer=B) and (owner=B, peer=A) are DIFFERENT rows by
--    design (per-user mirror rows for unread/last-read metadata). Messages
--    live on the CANONICAL (oldest) row resolved by send-message.
-- ---------------------------------------------------------------------------
CREATE UNIQUE INDEX IF NOT EXISTS conversations_owner_peer_uniq
  ON public.conversations(owner_id, peer_id)
  WHERE peer_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 5. PRESENCE GATING (H-spec): online / last seen / typing / recording are
--    only visible when a message request between the pair has been ACCEPTED
--    (or when viewing yourself). Replaces the previous wide-open
--    `up_select_all` (USING (true)) policy. Applies to both REST reads and
--    Realtime postgres_changes subscriptions.
-- ---------------------------------------------------------------------------
DROP POLICY IF EXISTS up_select_all ON public.user_presences;
DROP POLICY IF EXISTS up_select_self_or_accepted_peer ON public.user_presences;

CREATE POLICY up_select_self_or_accepted_peer ON public.user_presences
  FOR SELECT TO authenticated
  USING (
    user_id = auth.uid()
    OR EXISTS (
      SELECT 1
      FROM public.conversations c
      WHERE c.request_status = 'accepted'
        AND (
          (c.owner_id = auth.uid() AND c.peer_id = user_presences.user_id)
          OR (c.peer_id = auth.uid() AND c.owner_id = user_presences.user_id)
        )
    )
  );

-- ---------------------------------------------------------------------------
-- 6. call_sessions.receiver_id: text → uuid with FK to auth.users so call
--    history can join profiles for BOTH participants.
--    NOTE: the existing RLS policies on call_sessions AND call_events
--    reference `call_sessions.receiver_id` (call_events policies do so via
--    subquery), so they MUST be dropped before the type change and recreated
--    afterwards — `ALTER COLUMN TYPE` fails on any column dependency.
-- ---------------------------------------------------------------------------
DROP POLICY IF EXISTS call_events_insert_participants ON public.call_events;
DROP POLICY IF EXISTS call_events_select_participants ON public.call_events;
DROP POLICY IF EXISTS call_sessions_select_participants ON public.call_sessions;
DROP POLICY IF EXISTS call_sessions_update_participants ON public.call_sessions;
DROP POLICY IF EXISTS call_sessions_insert_caller ON public.call_sessions;

-- Stale ring entries older than a day are dead rows; remove them so the type
-- change cannot fail on malformed text values.
DELETE FROM public.call_sessions
WHERE status = 'calling' AND started_at < now() - interval '1 day';

UPDATE public.call_sessions
SET receiver_id = NULL
WHERE receiver_id IS NOT NULL
  AND receiver_id !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

ALTER TABLE public.call_sessions
  ALTER COLUMN receiver_id TYPE uuid USING receiver_id::uuid;

CREATE POLICY call_sessions_select_participants ON public.call_sessions
  FOR SELECT TO authenticated
  USING (caller_id = auth.uid() OR receiver_id = auth.uid());

CREATE POLICY call_sessions_update_participants ON public.call_sessions
  FOR UPDATE TO authenticated
  USING (caller_id = auth.uid() OR receiver_id = auth.uid())
  WITH CHECK (caller_id = auth.uid() OR receiver_id = auth.uid());

CREATE POLICY call_sessions_insert_caller ON public.call_sessions
  FOR INSERT TO authenticated
  WITH CHECK (caller_id = auth.uid());

CREATE POLICY call_events_select_participants ON public.call_events
  FOR SELECT TO authenticated
  USING (EXISTS (
    SELECT 1 FROM call_sessions cs
    WHERE cs.id = call_events.call_id
      AND (cs.caller_id = auth.uid() OR cs.receiver_id = auth.uid())
  ));

CREATE POLICY call_events_insert_participants ON public.call_events
  FOR INSERT TO authenticated
  WITH CHECK (
    auth.uid() = user_id
    AND EXISTS (
      SELECT 1 FROM call_sessions cs
      WHERE cs.id = call_events.call_id
        AND (cs.caller_id = auth.uid() OR cs.receiver_id = auth.uid())
    )
  );

ALTER TABLE public.call_sessions
  DROP CONSTRAINT IF EXISTS call_sessions_receiver_id_fkey;
ALTER TABLE public.call_sessions
  ADD CONSTRAINT call_sessions_receiver_id_fkey
  FOREIGN KEY (receiver_id) REFERENCES auth.users(id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS call_sessions_receiver_idx
  ON public.call_sessions(receiver_id, started_at DESC);
CREATE INDEX IF NOT EXISTS call_sessions_caller_started_idx
  ON public.call_sessions(caller_id, started_at DESC);

-- ---------------------------------------------------------------------------
-- 7. Message requests: fast inbox lookups for the receiver
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS msg_req_receiver_status_idx
  ON public.message_requests(receiver_id, status, created_at DESC);

-- ============================================================================
-- TRIGGER APP: REALTIME CALLING & LIVE STREAMING SCHEMA MIGRATION
-- Supports: 1-to-1 Audio/Video Calls, Agora WebRTC, Live Streams, Realtime Comments
-- ============================================================================

-- 1. EXTENSIONS
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 2. CALL SESSIONS TABLE
CREATE TABLE IF NOT EXISTS public.call_sessions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    caller_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    receiver_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    call_type VARCHAR(20) NOT NULL CHECK (call_type IN ('audio', 'video')),
    channel_name VARCHAR(120) NOT NULL UNIQUE,
    status VARCHAR(30) NOT NULL DEFAULT 'calling' CHECK (
        status IN ('calling', 'ringing', 'connecting', 'connected', 'ended', 'rejected', 'missed', 'failed', 'reconnecting')
    ),
    started_at TIMESTAMPTZ,
    answered_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    ended_reason VARCHAR(100),
    duration_seconds INT DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Indexes for call_sessions
CREATE INDEX IF NOT EXISTS idx_call_sessions_caller_id ON public.call_sessions(caller_id);
CREATE INDEX IF NOT EXISTS idx_call_sessions_receiver_id ON public.call_sessions(receiver_id);
CREATE INDEX IF NOT EXISTS idx_call_sessions_status ON public.call_sessions(status);
CREATE INDEX IF NOT EXISTS idx_call_sessions_channel_name ON public.call_sessions(channel_name);
CREATE INDEX IF NOT EXISTS idx_call_sessions_created_at ON public.call_sessions(created_at DESC);

-- 3. CALL EVENTS TABLE (Signaling & Lifecycle Audit)
CREATE TABLE IF NOT EXISTS public.call_events (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    call_id UUID NOT NULL REFERENCES public.call_sessions(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    event_type VARCHAR(50) NOT NULL,
    metadata JSONB DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_call_events_call_id ON public.call_events(call_id);
CREATE INDEX IF NOT EXISTS idx_call_events_created_at ON public.call_events(created_at ASC);

-- 4. LIVE STREAMS TABLE
CREATE TABLE IF NOT EXISTS public.live_streams (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    host_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    channel_name VARCHAR(120) NOT NULL UNIQUE,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    thumbnail_url TEXT,
    category VARCHAR(80) NOT NULL DEFAULT 'General',
    status VARCHAR(30) NOT NULL DEFAULT 'live' CHECK (
        status IN ('scheduled', 'starting', 'live', 'ended', 'cancelled', 'blocked')
    ),
    visibility VARCHAR(20) NOT NULL DEFAULT 'public' CHECK (
        visibility IN ('public', 'unlisted', 'private')
    ),
    viewer_count INT NOT NULL DEFAULT 0,
    total_likes INT NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ DEFAULT NOW(),
    ended_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_live_streams_host_id ON public.live_streams(host_id);
CREATE INDEX IF NOT EXISTS idx_live_streams_status ON public.live_streams(status);
CREATE INDEX IF NOT EXISTS idx_live_streams_category ON public.live_streams(category);
CREATE INDEX IF NOT EXISTS idx_live_streams_created_at ON public.live_streams(created_at DESC);

-- 5. LIVE STREAM COMMENTS TABLE
CREATE TABLE IF NOT EXISTS public.live_stream_comments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    stream_id UUID NOT NULL REFERENCES public.live_streams(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    user_name VARCHAR(120) NOT NULL,
    avatar_url TEXT,
    message VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_stream_comments_stream_id ON public.live_stream_comments(stream_id, created_at DESC);

-- 6. LIVE STREAM REACTIONS TABLE
CREATE TABLE IF NOT EXISTS public.live_stream_reactions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    stream_id UUID NOT NULL REFERENCES public.live_streams(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    reaction_type VARCHAR(30) NOT NULL DEFAULT 'heart',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_stream_reactions_stream_id ON public.live_stream_reactions(stream_id, created_at DESC);

-- 7. ROW LEVEL SECURITY (RLS) POLICIES
ALTER TABLE public.call_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.call_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.live_streams ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.live_stream_comments ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.live_stream_reactions ENABLE ROW LEVEL SECURITY;

-- Call Sessions Policies: Only caller and receiver can view or update their calls
CREATE POLICY "Users can view calls they participate in"
ON public.call_sessions FOR SELECT
TO authenticated
USING (auth.uid() = caller_id OR auth.uid() = receiver_id);

CREATE POLICY "Users can insert calls where they are caller"
ON public.call_sessions FOR INSERT
TO authenticated
WITH CHECK (auth.uid() = caller_id);

CREATE POLICY "Users can update calls they participate in"
ON public.call_sessions FOR UPDATE
TO authenticated
USING (auth.uid() = caller_id OR auth.uid() = receiver_id);

-- Call Events Policies
CREATE POLICY "Users can view events for calls they participate in"
ON public.call_events FOR SELECT
TO authenticated
USING (
    EXISTS (
        SELECT 1 FROM public.call_sessions cs
        WHERE cs.id = call_events.call_id
        AND (cs.caller_id = auth.uid() OR cs.receiver_id = auth.uid())
    )
);

CREATE POLICY "Users can insert events for calls they participate in"
ON public.call_events FOR INSERT
TO authenticated
WITH CHECK (
    auth.uid() = user_id AND
    EXISTS (
        SELECT 1 FROM public.call_sessions cs
        WHERE cs.id = call_events.call_id
        AND (cs.caller_id = auth.uid() OR cs.receiver_id = auth.uid())
    )
);

-- Live Streams Policies: Anyone can view active public streams; only host can insert/update
CREATE POLICY "Anyone can view public live streams"
ON public.live_streams FOR SELECT
TO authenticated
USING (visibility = 'public' OR host_id = auth.uid());

CREATE POLICY "Hosts can create live streams"
ON public.live_streams FOR INSERT
TO authenticated
WITH CHECK (auth.uid() = host_id);

CREATE POLICY "Hosts can update their live streams"
ON public.live_streams FOR UPDATE
TO authenticated
USING (auth.uid() = host_id);

-- Comments Policies: Any authenticated user can view comments on accessible streams
CREATE POLICY "Users can view comments on streams"
ON public.live_stream_comments FOR SELECT
TO authenticated
USING (
    EXISTS (
        SELECT 1 FROM public.live_streams ls
        WHERE ls.id = live_stream_comments.stream_id
        AND (ls.visibility = 'public' OR ls.host_id = auth.uid())
    )
);

CREATE POLICY "Users can add comments"
ON public.live_stream_comments FOR INSERT
TO authenticated
WITH CHECK (auth.uid() = user_id);

-- Reactions Policies
CREATE POLICY "Users can view reactions on streams"
ON public.live_stream_reactions FOR SELECT
TO authenticated
USING (true);

CREATE POLICY "Users can add reactions"
ON public.live_stream_reactions FOR INSERT
TO authenticated
WITH CHECK (auth.uid() = user_id);

-- 8. REALTIME REPLICATION PUBLICATION
-- Enable Realtime subscriptions for call status signaling, active streams, and comments
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'supabase_realtime') THEN
        CREATE PUBLICATION supabase_realtime;
    END IF;
END $$;

ALTER PUBLICATION supabase_realtime ADD TABLE public.call_sessions;
ALTER PUBLICATION supabase_realtime ADD TABLE public.live_streams;
ALTER PUBLICATION supabase_realtime ADD TABLE public.live_stream_comments;
ALTER PUBLICATION supabase_realtime ADD TABLE public.live_stream_reactions;

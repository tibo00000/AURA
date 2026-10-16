-- ============================================================================
-- AURA Migration 001: Activation de Row Level Security (RLS) & Politiques d'Isolation
-- ============================================================================

-- 1. Profiles
ALTER TABLE IF EXISTS profiles ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "Users can access own profile" ON profiles;
CREATE POLICY "Users can access own profile" ON profiles
    FOR ALL USING (auth.uid() = id);

-- 2. Playlists
ALTER TABLE IF EXISTS playlists ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "Users isolate own playlists" ON playlists;
CREATE POLICY "Users isolate own playlists" ON playlists
    FOR ALL USING (auth.uid() = user_id);

-- 3. Playlist Items (Table de liaison avec sous-requête sur playlists)
ALTER TABLE IF EXISTS playlist_items ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "Users isolate own playlist items" ON playlist_items;
CREATE POLICY "Users isolate own playlist items" ON playlist_items
    FOR ALL USING (
        playlist_id IN (SELECT id FROM playlists WHERE user_id = auth.uid())
    );

-- 4. Likes (Favoris)
ALTER TABLE IF EXISTS likes ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "Users isolate own likes" ON likes;
CREATE POLICY "Users isolate own likes" ON likes
    FOR ALL USING (auth.uid() = user_id);

-- 5. Playback Snapshots
ALTER TABLE IF EXISTS playback_snapshots ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "Users isolate own snapshot" ON playback_snapshots;
CREATE POLICY "Users isolate own snapshot" ON playback_snapshots
    FOR ALL USING (auth.uid() = user_id);

-- 6. User Settings
ALTER TABLE IF EXISTS user_settings ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "Users isolate own settings" ON user_settings;
CREATE POLICY "Users isolate own settings" ON user_settings
    FOR ALL USING (auth.uid() = user_id);

-- 7. Download Jobs
ALTER TABLE IF EXISTS download_jobs ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "Users isolate own download jobs" ON download_jobs;
CREATE POLICY "Users isolate own download jobs" ON download_jobs
    FOR ALL USING (auth.uid() = user_id);

-- 8. History Items
ALTER TABLE IF EXISTS history_items ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "Users isolate own history" ON history_items;
CREATE POLICY "Users isolate own history" ON history_items
    FOR ALL USING (auth.uid() = user_id);

-- 9. Listening Sessions
ALTER TABLE IF EXISTS listening_sessions ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "Users isolate own sessions" ON listening_sessions;
CREATE POLICY "Users isolate own sessions" ON listening_sessions
    FOR ALL USING (auth.uid() = user_id);

-- 10. Playback Events
ALTER TABLE IF EXISTS playback_events ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "Users isolate own playback events" ON playback_events;
CREATE POLICY "Users isolate own playback events" ON playback_events
    FOR ALL USING (auth.uid() = user_id);

-- 11. Processed Operations & Batches (Idempotence)
CREATE TABLE IF NOT EXISTS processed_batches (
    batch_id TEXT PRIMARY KEY,
    user_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE IF EXISTS processed_batches ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "Users isolate own processed batches" ON processed_batches;
CREATE POLICY "Users isolate own processed batches" ON processed_batches
    FOR ALL USING (auth.uid() = user_id);

CREATE TABLE IF NOT EXISTS processed_operations (
    operation_id TEXT PRIMARY KEY,
    user_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE IF EXISTS processed_operations ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "Users isolate own processed operations" ON processed_operations;
CREATE POLICY "Users isolate own processed operations" ON processed_operations
    FOR ALL USING (auth.uid() = user_id);

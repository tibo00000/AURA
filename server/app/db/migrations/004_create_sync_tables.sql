-- Migration: Create AURA Sync Tables
-- Date: 2026-05-29
-- Description: Creates profiles, user_settings, playlists, playlist_items, likes, playback_snapshots, history_items, listening_sessions, and playback_events tables with RLS and full Foreign Key constraints enabled.

-- Table profiles (Profil utilisateur AURA)
CREATE TABLE IF NOT EXISTS profiles (
    id UUID PRIMARY KEY,
    display_name TEXT,
    avatar_uri TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Table user_settings
CREATE TABLE IF NOT EXISTS user_settings (
    user_id UUID PRIMARY KEY REFERENCES profiles(id) ON DELETE CASCADE,
    sync_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    online_search_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    online_search_network_policy TEXT NOT NULL DEFAULT 'any_network',
    stats_sync_network_policy TEXT NOT NULL DEFAULT 'wifi_only',
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Table playlists
CREATE TABLE IF NOT EXISTS playlists (
    id TEXT PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    cover_uri TEXT,
    is_pinned BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Table playlist_items
CREATE TABLE IF NOT EXISTS playlist_items (
    id TEXT PRIMARY KEY,
    playlist_id TEXT NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
    track_id TEXT NOT NULL,
    position INTEGER NOT NULL,
    added_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    added_from_context_type TEXT,
    added_from_context_id TEXT,
    CONSTRAINT unique_playlist_position UNIQUE(playlist_id, position)
);

-- Table likes
CREATE TABLE IF NOT EXISTS likes (
    user_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    track_id TEXT NOT NULL,
    liked_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    source_context_type TEXT,
    source_context_id TEXT,
    PRIMARY KEY (user_id, track_id)
);

-- Table playback_snapshots
CREATE TABLE IF NOT EXISTS playback_snapshots (
    user_id UUID PRIMARY KEY REFERENCES profiles(id) ON DELETE CASCADE,
    current_track_id TEXT,
    playback_context_type TEXT,
    playback_context_id TEXT,
    playback_context_index INTEGER,
    position_ms INTEGER NOT NULL DEFAULT 0,
    shuffle_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    repeat_mode TEXT NOT NULL DEFAULT 'none',
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Table history_items
CREATE TABLE IF NOT EXISTS history_items (
    id TEXT PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    track_id TEXT NOT NULL,
    listening_session_id TEXT,
    played_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    completion_percent REAL,
    was_skipped BOOLEAN NOT NULL DEFAULT FALSE,
    source_context_type TEXT,
    source_context_id TEXT
);

-- Table listening_sessions
CREATE TABLE IF NOT EXISTS listening_sessions (
    id TEXT PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    started_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMP WITH TIME ZONE,
    source_type TEXT,
    source_id TEXT,
    device_type TEXT,
    network_type TEXT,
    total_listening_ms INTEGER NOT NULL DEFAULT 0
);

-- Table playback_events
CREATE TABLE IF NOT EXISTS playback_events (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES listening_sessions(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    track_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    position_start_ms INTEGER,
    position_end_ms INTEGER,
    completion_percent REAL,
    skip_reason TEXT,
    liked_during_playback BOOLEAN NOT NULL DEFAULT FALSE
);

-- Activation RLS sur toutes les tables
ALTER TABLE profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE playlists ENABLE ROW LEVEL SECURITY;
ALTER TABLE playlist_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE likes ENABLE ROW LEVEL SECURITY;
ALTER TABLE playback_snapshots ENABLE ROW LEVEL SECURITY;
ALTER TABLE history_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE listening_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE playback_events ENABLE ROW LEVEL SECURITY;

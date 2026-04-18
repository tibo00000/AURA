-- Migration: Create Catalog Tables (Artists, Albums, Tracks)
-- Date: 2026-04-18
-- Description: Create core catalog tables for online music provider data

-- Artists table
CREATE TABLE IF NOT EXISTS artists (
    id TEXT PRIMARY KEY,
    display_artist_name TEXT NOT NULL,
    normalized_name TEXT NOT NULL,
    provider_references JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_artists_normalized_name ON artists(normalized_name);
CREATE INDEX idx_artists_display_name ON artists(display_artist_name);

-- Albums table
CREATE TABLE IF NOT EXISTS albums (
    id TEXT PRIMARY KEY,
    display_album_title TEXT NOT NULL,
    normalized_title TEXT NOT NULL,
    artist_id TEXT NOT NULL REFERENCES artists(id) ON DELETE RESTRICT,
    provider_references JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_albums_normalized_title ON albums(normalized_title);
CREATE INDEX idx_albums_display_title ON albums(display_album_title);
CREATE INDEX idx_albums_artist_id ON albums(artist_id);

-- Tracks table
CREATE TABLE IF NOT EXISTS tracks (
    id TEXT PRIMARY KEY,
    display_title TEXT NOT NULL,
    normalized_title TEXT NOT NULL,
    album_id TEXT NOT NULL REFERENCES albums(id) ON DELETE RESTRICT,
    artist_id TEXT NOT NULL REFERENCES artists(id) ON DELETE RESTRICT,
    duration_ms INTEGER,
    provider_references JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_tracks_normalized_title ON tracks(normalized_title);
CREATE INDEX idx_tracks_display_title ON tracks(display_title);
CREATE INDEX idx_tracks_album_id ON tracks(album_id);
CREATE INDEX idx_tracks_artist_id ON tracks(artist_id);

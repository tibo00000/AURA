-- Migration: Create Mapping Tables (track_source_links)
-- Date: 2026-04-18
-- Description: Create mapping tables for linking AURA backend IDs to provider IDs (Deezer, etc.)

-- Track source links: maps AURA track ID to provider (Deezer, etc.) track ID
-- Used for search result enrichment, streaming URL resolution, and metadata sync
CREATE TABLE IF NOT EXISTS track_source_links (
    id TEXT PRIMARY KEY,
    track_id TEXT NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
    provider_name TEXT NOT NULL,
    provider_track_id TEXT NOT NULL,
    provider_album_id TEXT,
    provider_artist_id TEXT,
    usage_type TEXT NOT NULL,
    match_score NUMERIC,
    is_active_for_usage BOOLEAN DEFAULT TRUE,
    metadata_json JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Composite unique constraint: each track has only one active mapping per usage type + provider
CREATE UNIQUE INDEX idx_track_source_links_unique ON track_source_links(
    track_id, usage_type, provider_name, provider_track_id
) WHERE is_active_for_usage = TRUE;

-- Indexes for query performance
CREATE INDEX idx_track_source_links_track_id ON track_source_links(track_id);
CREATE INDEX idx_track_source_links_provider ON track_source_links(provider_name);
CREATE INDEX idx_track_source_links_provider_track_id ON track_source_links(provider_track_id);
CREATE INDEX idx_track_source_links_usage_type ON track_source_links(usage_type);
CREATE INDEX idx_track_source_links_match_score ON track_source_links(match_score DESC);

-- Note: AND-005 will enhance this table with persistent mapping logic between local Android MediaStore IDs
-- and backend AURA IDs. For now, this structure is created to support future sync operations.

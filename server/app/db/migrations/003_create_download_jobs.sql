-- Migration: Create Download Jobs Table
-- Date: 2026-05-29
-- Description: Table to persist asynchronous download jobs states and candidates.

CREATE TABLE IF NOT EXISTS download_jobs (
    id TEXT PRIMARY KEY,
    user_id UUID NOT NULL,
    track_id TEXT NOT NULL,
    provider_name TEXT NOT NULL,
    status TEXT NOT NULL,
    progress_percent REAL DEFAULT 0.0,
    error_code TEXT,
    error_message TEXT,
    attempt_count INTEGER DEFAULT 1,
    candidates JSONB DEFAULT '[]'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    archived_at TIMESTAMP WITH TIME ZONE
);

-- Index pour accélérer le listage par utilisateur trié par date décroissante
CREATE INDEX IF NOT EXISTS idx_download_jobs_user_created ON download_jobs(user_id, created_at DESC);
-- Index pour filtrer rapidement par statut
CREATE INDEX IF NOT EXISTS idx_download_jobs_status ON download_jobs(status);

-- Activation de la sécurité au niveau des lignes (RLS)
-- Le serveur FastAPI utilisera la clé de rôle de service (service_role) qui contourne RLS,
-- protégeant ainsi la table de tout accès anonyme direct de clients externes.
ALTER TABLE download_jobs ENABLE ROW LEVEL SECURITY;

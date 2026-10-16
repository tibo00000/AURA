-- Migration: Persist the operation that created a download job
-- Date: 2026-10-15
-- Description: Lets clients distinguish ordinary downloads from explicit audio reassignments.

ALTER TABLE download_jobs
    ADD COLUMN IF NOT EXISTS operation_type TEXT NOT NULL DEFAULT 'download';

ALTER TABLE download_jobs
    DROP CONSTRAINT IF EXISTS download_jobs_operation_type_check;

ALTER TABLE download_jobs
    ADD CONSTRAINT download_jobs_operation_type_check
    CHECK (operation_type IN ('download', 'manual_reassign'));

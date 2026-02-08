-- Migration: V22__add_site_retention_and_error_logs_cascade.sql
-- Description: Add retention policy to sites and cascade deletes for batch error logs
-- Author: Data Forge Team
-- Date: 2026-02-05

-- Add retention policy to sites
ALTER TABLE sites
    ADD COLUMN retention_days INTEGER NOT NULL DEFAULT 45;

COMMENT ON COLUMN sites.retention_days IS 'Retention period in days for batch cleanup (per site)';

-- Update error_logs foreign key to cascade deletes when batches are removed
ALTER TABLE error_logs
    DROP CONSTRAINT IF EXISTS error_logs_batch_id_fkey;

ALTER TABLE error_logs
    ADD CONSTRAINT error_logs_batch_id_fkey
    FOREIGN KEY (batch_id) REFERENCES batches(id) ON DELETE CASCADE;

-- Index to accelerate retention cleanup queries
CREATE INDEX IF NOT EXISTS idx_batches_site_status_started_at
    ON batches(site_id, status, started_at);

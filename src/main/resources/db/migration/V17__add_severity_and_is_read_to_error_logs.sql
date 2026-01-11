-- V17: Add severity and is_read columns to error_logs table
-- Feature: 016-global-error-handling
-- Date: 2026-01-11

-- Add severity column with default 'ERROR'
ALTER TABLE error_logs
ADD COLUMN severity VARCHAR(20) NOT NULL DEFAULT 'ERROR';

-- Add is_read column with default true (existing errors considered read)
ALTER TABLE error_logs
ADD COLUMN is_read BOOLEAN NOT NULL DEFAULT true;

-- Change default for new rows: is_read = false
ALTER TABLE error_logs
ALTER COLUMN is_read SET DEFAULT false;

-- Partial index for efficient unread global errors query
-- Optimizes: SELECT COUNT(*) WHERE batch_id IS NULL AND is_read = false
CREATE INDEX idx_error_logs_global_unread
ON error_logs (site_id)
WHERE batch_id IS NULL AND is_read = false;

-- Add check constraint for severity values
ALTER TABLE error_logs
ADD CONSTRAINT chk_error_logs_severity
CHECK (severity IN ('CRITICAL', 'ERROR', 'WARNING', 'INFO'));

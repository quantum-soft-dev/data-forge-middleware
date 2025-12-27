-- Add metadata column to plugin_audit_logs for storing structured event details
-- This enables rich logging of SQL generation events with batchId, stats, s3Key etc.

-- Add metadata column (JSONB for flexible structured data)
ALTER TABLE plugin_audit_logs ADD COLUMN IF NOT EXISTS metadata JSONB;

-- Add GIN index for efficient JSONB queries (e.g., find all logs for a specific batchId)
CREATE INDEX IF NOT EXISTS idx_plugin_audit_logs_metadata
    ON plugin_audit_logs USING GIN(metadata);

-- Add partial index for quick lookup of logs with metadata (most logs won't have it)
CREATE INDEX IF NOT EXISTS idx_plugin_audit_logs_has_metadata
    ON plugin_audit_logs(id)
    WHERE metadata IS NOT NULL;

-- Add comment for documentation
COMMENT ON COLUMN plugin_audit_logs.metadata IS
    'Structured event metadata (JSONB). Contains batchId, siteId, stats for SQL generation events.';

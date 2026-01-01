-- V14__add_plugin_history_fields.sql
-- Add superseded tracking fields to plugin_sql_generations for regeneration history

-- Add superseded flag to mark generations that have been replaced
ALTER TABLE plugin_sql_generations
ADD COLUMN superseded BOOLEAN NOT NULL DEFAULT FALSE;

-- Add self-referential foreign key to track which generation replaced this one
ALTER TABLE plugin_sql_generations
ADD COLUMN superseded_by UUID;

-- Self-referential foreign key constraint
ALTER TABLE plugin_sql_generations
ADD CONSTRAINT fk_plugin_sql_generations_superseded_by
FOREIGN KEY (superseded_by)
REFERENCES plugin_sql_generations(id)
ON DELETE SET NULL;

-- Index for finding active (non-superseded) generations efficiently
-- Partial index only includes non-superseded rows for optimal query performance
CREATE INDEX idx_plugin_sql_generations_active
ON plugin_sql_generations(account_plugin_id, superseded)
WHERE superseded = FALSE;

-- Index for finding generations by account_plugin_id (for listing and counting)
CREATE INDEX idx_plugin_sql_generations_account_plugin
ON plugin_sql_generations(account_plugin_id, created_at DESC);

-- Note: PluginActionType is Java enum stored as VARCHAR
-- New action types (PLUGIN_HISTORY_CLEARED, SQL_REGENERATION_*) are added in Java code
-- No migration needed for enum values as PostgreSQL stores them as strings

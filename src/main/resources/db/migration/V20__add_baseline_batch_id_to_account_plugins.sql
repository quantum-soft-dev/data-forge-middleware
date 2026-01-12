-- Add baseline_batch_id column to account_plugins table
-- This column tracks the "baseline" batch for CSV file initialization.
-- When a plugin is activated or reinitialized, the latest completed batch
-- becomes the baseline. SQL generation is skipped for the baseline batch
-- because clients download CSV files directly for initialization.

ALTER TABLE account_plugins ADD COLUMN baseline_batch_id UUID;

-- Add foreign key constraint to batches table
ALTER TABLE account_plugins ADD CONSTRAINT fk_account_plugins_baseline_batch
    FOREIGN KEY (baseline_batch_id) REFERENCES batches(id) ON DELETE SET NULL;

-- Add index for efficient lookup
CREATE INDEX idx_account_plugins_baseline_batch ON account_plugins(baseline_batch_id)
    WHERE baseline_batch_id IS NOT NULL;

COMMENT ON COLUMN account_plugins.baseline_batch_id IS
    'The batch ID used as baseline for initialization. SQL generation is skipped for this batch. '
    'Clients should download CSV files via /sites/{siteId}/files endpoint for baseline data.';

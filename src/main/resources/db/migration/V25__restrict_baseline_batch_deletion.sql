-- V25__restrict_baseline_batch_deletion.sql
-- Prevent deleting batches that are referenced as a plugin baseline.
--
-- Rationale:
-- baseline_batch_id is used for CSV initialization. If a baseline batch is deleted,
-- clients may no longer be able to initialize reliably. Retention cleanup also
-- excludes baseline batches, but this FK prevents races where a batch becomes a
-- baseline between candidate selection and deletion.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_account_plugins_baseline_batch'
    ) THEN
        ALTER TABLE account_plugins DROP CONSTRAINT fk_account_plugins_baseline_batch;
    END IF;
END $$;

ALTER TABLE account_plugins
    ADD CONSTRAINT fk_account_plugins_baseline_batch
        FOREIGN KEY (baseline_batch_id)
        REFERENCES batches(id)
        ON DELETE RESTRICT;

-- Ensure the partial index exists for efficient baseline lookup.
CREATE INDEX IF NOT EXISTS idx_account_plugins_baseline_batch
    ON account_plugins(baseline_batch_id)
    WHERE baseline_batch_id IS NOT NULL;


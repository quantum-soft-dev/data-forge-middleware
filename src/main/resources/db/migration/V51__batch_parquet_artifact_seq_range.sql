-- Migration: V51__batch_parquet_artifact_seq_range.sql
-- Description: Persist the per-table seq range on completed-batch Parquet artifacts (041, #109).
-- Old READY rows stay NULL. The catalog emits that as lastSeq=null (never skippable);
-- it does not live-query changelog_segments after retention.

ALTER TABLE batch_parquet_artifacts
    ADD COLUMN first_seq BIGINT,
    ADD COLUMN last_seq BIGINT;

ALTER TABLE batch_parquet_artifacts
    ADD CONSTRAINT chk_batch_parquet_artifact_seq_range CHECK (
        (first_seq IS NULL AND last_seq IS NULL)
        OR (first_seq IS NOT NULL AND last_seq IS NOT NULL AND last_seq >= first_seq)
    );

-- Keyset support for the Parquet Export batch catalog (site + ready_at + s3_key).
-- ABANDONED rows have NULL ready_at/s3_key and do not use this index.
CREATE INDEX idx_batch_parquet_artifacts_catalog
    ON batch_parquet_artifacts (site_id, ready_at, s3_key);

COMMENT ON COLUMN batch_parquet_artifacts.first_seq IS
    'Inclusive first sequence of this table in the batch; set when the artifact becomes READY.';
COMMENT ON COLUMN batch_parquet_artifacts.last_seq IS
    'Inclusive last sequence of this table in the batch; set when the artifact becomes READY.';

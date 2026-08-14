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

-- Catalog listing is UNION ALL of READY (ready_at, s3_key) and ABANDONED
-- (updated_at, 'abandoned/' || id). Indexes match those ORDER BY expressions
-- without a leading site_id so the default all-sites account listing can use them.
CREATE INDEX idx_batch_parquet_artifacts_catalog_ready
    ON batch_parquet_artifacts (ready_at, s3_key)
    WHERE status = 'READY';

CREATE INDEX idx_batch_parquet_artifacts_catalog_abandoned
    ON batch_parquet_artifacts (
        updated_at,
        (('abandoned/' || id::text))
    )
    WHERE status = 'ABANDONED';

COMMENT ON COLUMN batch_parquet_artifacts.first_seq IS
    'Inclusive first sequence of this table in the batch; set when the artifact becomes READY.';
COMMENT ON COLUMN batch_parquet_artifacts.last_seq IS
    'Inclusive last sequence of this table in the batch; set when the artifact becomes READY.';

-- Single-row catalog watermark: GREATEST(previous + 1µs, clock_timestamp()) so a replica
-- whose wall clock lags cannot stamp ready_at/updated_at at or before an already-visible row.
CREATE TABLE batch_parquet_catalog_watermark (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    published_at TIMESTAMP NOT NULL
);

INSERT INTO batch_parquet_catalog_watermark (id, published_at)
VALUES (1, TIMESTAMP '-infinity');

COMMENT ON TABLE batch_parquet_catalog_watermark IS
    'Monotonic published_at for parquet-export catalog visibility (READY/ABANDONED).';

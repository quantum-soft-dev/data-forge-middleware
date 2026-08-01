-- Migration: V49__batch_parquet_artifacts.sql
-- Description: Durable per-batch/table Parquet finalization manifest (036, issue #93).

CREATE TABLE batch_parquet_artifacts (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES batches(id) ON DELETE CASCADE,
    site_id UUID NOT NULL REFERENCES sites(id) ON DELETE CASCADE,
    table_name VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    s3_key VARCHAR(1000),
    row_count BIGINT,
    file_size BIGINT,
    checksum VARCHAR(64),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ready_at TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_batch_parquet_artifact UNIQUE (batch_id, table_name),
    CONSTRAINT chk_batch_parquet_artifact_status
        CHECK (status IN ('PENDING', 'BUILDING', 'READY', 'FAILED', 'ABANDONED')),
    CONSTRAINT chk_batch_parquet_artifact_ready CHECK (
        (status = 'READY' AND s3_key IS NOT NULL AND row_count IS NOT NULL
            AND file_size IS NOT NULL AND checksum IS NOT NULL AND ready_at IS NOT NULL)
        OR status <> 'READY'
    )
);

-- Claim index. BUILDING is included because a claim is committed before its build runs: a worker
-- that dies leaves the row BUILDING, and it is reclaimed once its build lease expires.
CREATE INDEX idx_batch_parquet_artifacts_claim
    ON batch_parquet_artifacts (status, updated_at, created_at)
    WHERE status IN ('PENDING', 'FAILED', 'BUILDING');

CREATE INDEX idx_batch_parquet_artifacts_site
    ON batch_parquet_artifacts (site_id);

COMMENT ON TABLE batch_parquet_artifacts IS
    'Durable manifest and retry queue for one unified completed-batch Parquet per table.';

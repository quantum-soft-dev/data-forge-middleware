-- Migration: V3__create_batches_table.sql
-- Description: Create batches table with lifecycle state management and optimistic locking
-- Author: Data Forge Team
-- Date: 2025-11-08
-- Consolidated from: V3, V6, V8, V15

CREATE TABLE batches (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL REFERENCES sites(id),
    account_id UUID NOT NULL REFERENCES accounts(id),
    status VARCHAR(50) NOT NULL DEFAULT 'IN_PROGRESS',
    s3_path VARCHAR(500) NOT NULL,
    uploaded_files_count INTEGER NOT NULL DEFAULT 0 CHECK (uploaded_files_count >= 0),
    total_size BIGINT NOT NULL DEFAULT 0 CHECK (total_size >= 0),
    has_errors BOOLEAN NOT NULL DEFAULT false,
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_batch_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'NOT_COMPLETED', 'FAILED', 'CANCELLED'))
);

-- Indexes for performance optimization
CREATE INDEX idx_batches_site_id ON batches(site_id);
CREATE INDEX idx_batches_account_id ON batches(account_id);
CREATE INDEX idx_batches_status ON batches(status);
CREATE INDEX idx_batches_started_at ON batches(started_at);

-- Composite index for cursor-based pagination (upload history feature)
-- Supports query pattern: WHERE site_id = ? AND (started_at < ? OR (started_at = ? AND id < ?))
-- Performance: O(log n) lookup + sequential scan vs O(n) for OFFSET-based pagination
CREATE INDEX idx_batches_site_started_id ON batches(site_id, started_at DESC, id DESC);

-- Partial unique index: Only one IN_PROGRESS batch per site
CREATE UNIQUE INDEX idx_batches_active_per_site ON batches(site_id)
    WHERE status = 'IN_PROGRESS';

-- Comments for documentation
COMMENT ON TABLE batches IS 'File upload sessions with lifecycle state management';
COMMENT ON COLUMN batches.id IS 'Unique batch identifier (batchId)';
COMMENT ON COLUMN batches.site_id IS 'Owning site reference';
COMMENT ON COLUMN batches.account_id IS 'Denormalized account reference for efficient querying';
COMMENT ON COLUMN batches.status IS 'Lifecycle state (IN_PROGRESS, COMPLETED, NOT_COMPLETED, FAILED, CANCELLED)';
COMMENT ON COLUMN batches.s3_path IS 'Full S3 directory path for uploaded files';
COMMENT ON COLUMN batches.uploaded_files_count IS 'Counter for uploaded files (incremented atomically)';
COMMENT ON COLUMN batches.total_size IS 'Total bytes uploaded';
COMMENT ON COLUMN batches.has_errors IS 'Flag set when errors are logged (never reset to false)';
COMMENT ON COLUMN batches.started_at IS 'Batch creation time (used for timeout calculation)';
COMMENT ON COLUMN batches.completed_at IS 'Completion timestamp (set when status changes from IN_PROGRESS)';
COMMENT ON COLUMN batches.created_at IS 'Record creation timestamp';
COMMENT ON COLUMN batches.version IS 'Optimistic locking version for concurrent update prevention (JPA @Version)';

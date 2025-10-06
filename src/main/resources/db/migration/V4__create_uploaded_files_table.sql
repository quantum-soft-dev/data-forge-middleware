-- Migration: V4__create_uploaded_files_table.sql
-- Description: Create uploaded_files table with checksum tracking
-- Author: Data Forge Team
-- Date: 2025-10-06

CREATE TABLE uploaded_files (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES batches(id) ON DELETE CASCADE,
    original_file_name VARCHAR(500) NOT NULL,
    s3_key VARCHAR(1000) NOT NULL UNIQUE,
    file_size BIGINT NOT NULL CHECK (file_size > 0),
    content_type VARCHAR(100) NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    uploaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for performance optimization
CREATE INDEX idx_uploaded_files_batch_id ON uploaded_files(batch_id);

-- Composite unique index: filename uniqueness per batch
CREATE UNIQUE INDEX idx_uploaded_files_batch_filename ON uploaded_files(batch_id, original_file_name);

-- Comments for documentation
COMMENT ON TABLE uploaded_files IS 'Metadata for each file uploaded to a batch';
COMMENT ON COLUMN uploaded_files.id IS 'Unique file metadata identifier (UUID)';
COMMENT ON COLUMN uploaded_files.batch_id IS 'Parent batch reference (cascade delete)';
COMMENT ON COLUMN uploaded_files.original_file_name IS 'Client-provided filename (e.g., sales.csv.gz)';
COMMENT ON COLUMN uploaded_files.s3_key IS 'Full S3 object key ({s3Path}{originalFileName})';
COMMENT ON COLUMN uploaded_files.file_size IS 'File size in bytes (must be > 0)';
COMMENT ON COLUMN uploaded_files.content_type IS 'MIME type (usually application/gzip)';
COMMENT ON COLUMN uploaded_files.checksum IS 'MD5 hash for integrity verification';
COMMENT ON COLUMN uploaded_files.uploaded_at IS 'Upload completion timestamp';

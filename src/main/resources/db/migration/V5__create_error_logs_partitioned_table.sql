-- Migration: V5__create_error_logs_partitioned_table.sql
-- Description: Create partitioned error_logs table with JSONB metadata
-- Author: Data Forge Team
-- Date: 2025-10-06

CREATE TABLE error_logs (
    id UUID NOT NULL,
    site_id UUID NOT NULL REFERENCES sites(id),
    batch_id UUID REFERENCES batches(id),
    type VARCHAR(100) NOT NULL,
    title VARCHAR(500) NOT NULL,
    message TEXT NOT NULL,
    stack_trace TEXT,
    client_version VARCHAR(50),
    metadata JSONB,
    occurred_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id, occurred_at)  -- Composite PK for partitioning
) PARTITION BY RANGE (occurred_at);

-- Indexes for performance optimization
CREATE INDEX idx_error_logs_site_id ON error_logs(site_id);
CREATE INDEX idx_error_logs_batch_id ON error_logs(batch_id);
CREATE INDEX idx_error_logs_type ON error_logs(type);
CREATE INDEX idx_error_logs_occurred_at ON error_logs(occurred_at);

-- GIN index for JSONB metadata queries
CREATE INDEX idx_error_logs_metadata_gin ON error_logs USING GIN (metadata);

-- Initial partitions (current and next month)
CREATE TABLE error_logs_2025_10 PARTITION OF error_logs
    FOR VALUES FROM ('2025-10-01') TO ('2025-11-01');

CREATE TABLE error_logs_2025_11 PARTITION OF error_logs
    FOR VALUES FROM ('2025-11-01') TO ('2025-12-01');

-- Comments for documentation
COMMENT ON TABLE error_logs IS 'Time-partitioned error log records with flexible metadata';
COMMENT ON COLUMN error_logs.id IS 'Unique error identifier (UUID)';
COMMENT ON COLUMN error_logs.site_id IS 'Reporting site reference';
COMMENT ON COLUMN error_logs.batch_id IS 'Associated batch (null for standalone errors)';
COMMENT ON COLUMN error_logs.type IS 'Error category (e.g., FileReadError, ConfigurationError)';
COMMENT ON COLUMN error_logs.title IS 'Brief error summary';
COMMENT ON COLUMN error_logs.message IS 'Detailed error description';
COMMENT ON COLUMN error_logs.stack_trace IS 'Exception stack trace (optional)';
COMMENT ON COLUMN error_logs.client_version IS 'Client application version (optional)';
COMMENT ON COLUMN error_logs.metadata IS 'Additional structured data (JSONB)';
COMMENT ON COLUMN error_logs.occurred_at IS 'Error occurrence timestamp (partitioning key)';
COMMENT ON COLUMN error_logs.created_at IS 'Record insertion timestamp';

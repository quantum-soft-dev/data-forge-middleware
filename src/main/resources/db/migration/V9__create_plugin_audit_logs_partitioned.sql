-- V9: Create partitioned plugin_audit_logs table for Plugin System feature
-- Feature: 013-plugin-system
-- Date: 2025-12-16
-- Pattern: Follows existing error_logs partitioning pattern from V5

-- =============================================================================
-- plugin_audit_logs: Audit trail for plugin API calls and event executions
-- Partitioned by month (occurred_at) for efficient time-based queries
-- =============================================================================
CREATE TABLE plugin_audit_logs (
    id BIGSERIAL,
    plugin_id VARCHAR(64) NOT NULL,
    account_id UUID,
    client_id VARCHAR(64),
    action_type VARCHAR(50) NOT NULL,
    request_body_hash VARCHAR(64),
    request_body_size INTEGER,
    response_status INTEGER,
    success BOOLEAN NOT NULL,
    error_message VARCHAR(500),
    ip_address VARCHAR(45),
    user_agent VARCHAR(512),
    duration_ms BIGINT,
    occurred_at TIMESTAMP NOT NULL,

    PRIMARY KEY (id, occurred_at),

    CONSTRAINT chk_plugin_audit_logs_action_type CHECK (
        action_type IN ('ACTIVATE', 'DEACTIVATE', 'REACTIVATE', 'EVENT_DISPATCHED', 'EVENT_FAILED', 'EVENT_TIMEOUT')
    ),
    CONSTRAINT chk_plugin_audit_logs_error_message CHECK (
        (success = true) OR (success = false AND error_message IS NOT NULL)
    )
) PARTITION BY RANGE (occurred_at);

COMMENT ON TABLE plugin_audit_logs IS 'Audit trail for plugin operations (API calls, event dispatches). Partitioned by month.';
COMMENT ON COLUMN plugin_audit_logs.plugin_id IS 'Plugin identifier for the operation';
COMMENT ON COLUMN plugin_audit_logs.account_id IS 'Target account (null for non-account operations)';
COMMENT ON COLUMN plugin_audit_logs.client_id IS 'OAuth client that initiated the request';
COMMENT ON COLUMN plugin_audit_logs.action_type IS 'Type of operation: ACTIVATE, DEACTIVATE, REACTIVATE, EVENT_DISPATCHED, EVENT_FAILED, EVENT_TIMEOUT';
COMMENT ON COLUMN plugin_audit_logs.request_body_hash IS 'SHA-256 hash of request body (base64 encoded) - NEVER store plaintext per FR-014';
COMMENT ON COLUMN plugin_audit_logs.request_body_size IS 'Size of request body in bytes';
COMMENT ON COLUMN plugin_audit_logs.response_status IS 'HTTP response status code for API operations';
COMMENT ON COLUMN plugin_audit_logs.success IS 'Whether the operation succeeded';
COMMENT ON COLUMN plugin_audit_logs.error_message IS 'Error message if operation failed (required when success=false)';
COMMENT ON COLUMN plugin_audit_logs.ip_address IS 'Client IP address (supports IPv6)';
COMMENT ON COLUMN plugin_audit_logs.user_agent IS 'Client user agent string';
COMMENT ON COLUMN plugin_audit_logs.duration_ms IS 'Operation duration in milliseconds';
COMMENT ON COLUMN plugin_audit_logs.occurred_at IS 'When the operation occurred (partition key)';

-- =============================================================================
-- Create partitions for current and next 3 months
-- =============================================================================
CREATE TABLE plugin_audit_logs_2025_12 PARTITION OF plugin_audit_logs
    FOR VALUES FROM ('2025-12-01') TO ('2026-01-01');

CREATE TABLE plugin_audit_logs_2026_01 PARTITION OF plugin_audit_logs
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');

CREATE TABLE plugin_audit_logs_2026_02 PARTITION OF plugin_audit_logs
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');

CREATE TABLE plugin_audit_logs_2026_03 PARTITION OF plugin_audit_logs
    FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');

-- =============================================================================
-- Indexes (created on parent table, automatically applied to partitions)
-- =============================================================================
CREATE INDEX idx_plugin_audit_logs_plugin_id ON plugin_audit_logs(plugin_id);
CREATE INDEX idx_plugin_audit_logs_account_id ON plugin_audit_logs(account_id) WHERE account_id IS NOT NULL;
CREATE INDEX idx_plugin_audit_logs_occurred_at ON plugin_audit_logs(occurred_at DESC);
CREATE INDEX idx_plugin_audit_logs_action_type ON plugin_audit_logs(action_type);
CREATE INDEX idx_plugin_audit_logs_success ON plugin_audit_logs(success) WHERE success = false;

-- Composite index for filtered queries (per PR review recommendation)
CREATE INDEX idx_plugin_audit_logs_filters ON plugin_audit_logs(plugin_id, account_id, action_type, success, occurred_at DESC);

-- =============================================================================
-- Function to create future partitions (can be called by scheduled job)
-- =============================================================================
CREATE OR REPLACE FUNCTION create_plugin_audit_logs_partition(partition_date DATE)
RETURNS VOID AS $$
DECLARE
    partition_name TEXT;
    start_date DATE;
    end_date DATE;
BEGIN
    start_date := DATE_TRUNC('month', partition_date);
    end_date := start_date + INTERVAL '1 month';
    partition_name := 'plugin_audit_logs_' || TO_CHAR(start_date, 'YYYY_MM');

    -- Check if partition already exists
    IF NOT EXISTS (
        SELECT 1 FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE c.relname = partition_name
        AND n.nspname = 'public'
    ) THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF plugin_audit_logs FOR VALUES FROM (%L) TO (%L)',
            partition_name,
            start_date,
            end_date
        );
        RAISE NOTICE 'Created partition: %', partition_name;
    END IF;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION create_plugin_audit_logs_partition IS 'Creates a monthly partition for plugin_audit_logs if it does not exist';

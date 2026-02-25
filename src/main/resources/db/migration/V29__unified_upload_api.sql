-- V29: Unified Data Upload API
-- Feature: 021-unified-upload-api
-- Adds heartbeat/directive fields to sites, batch type/schema version to batches,
-- and creates client_diagnostic_logs table.

-- 1. Add heartbeat and directive columns to sites
ALTER TABLE sites ADD COLUMN last_heartbeat_at TIMESTAMP;
ALTER TABLE sites ADD COLUMN force_full_upload BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE sites ADD COLUMN force_full_upload_reason VARCHAR(50);
ALTER TABLE sites ADD COLUMN force_full_upload_message TEXT;
ALTER TABLE sites ADD COLUMN force_full_upload_set_at TIMESTAMP;
ALTER TABLE sites ADD COLUMN force_full_upload_set_by VARCHAR(255);
ALTER TABLE sites ADD COLUMN request_logs BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE sites ADD COLUMN request_logs_message TEXT;

-- 2. Add batch type and schema version to batches
ALTER TABLE batches ADD COLUMN batch_type VARCHAR(20);
ALTER TABLE batches ADD COLUMN schema_version INTEGER;
ALTER TABLE batches ADD COLUMN expected_file_count INTEGER;
ALTER TABLE batches ADD COLUMN description TEXT;

-- 3. Create client_diagnostic_logs table
CREATE TABLE client_diagnostic_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id UUID NOT NULL REFERENCES sites(id) ON DELETE CASCADE,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    s3_key VARCHAR(500) NOT NULL,
    filename VARCHAR(255) NOT NULL,
    file_size BIGINT NOT NULL CHECK (file_size > 0),
    content_type VARCHAR(100),
    client_version VARCHAR(100),
    os VARCHAR(200),
    period_from TIMESTAMP,
    period_to TIMESTAMP,
    tags JSONB,
    description TEXT,
    uploaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_client_diagnostic_logs_site_id ON client_diagnostic_logs(site_id);
CREATE INDEX idx_client_diagnostic_logs_account_id ON client_diagnostic_logs(account_id);
CREATE INDEX idx_client_diagnostic_logs_expires_at ON client_diagnostic_logs(expires_at);
CREATE INDEX idx_client_diagnostic_logs_uploaded_at ON client_diagnostic_logs(uploaded_at);

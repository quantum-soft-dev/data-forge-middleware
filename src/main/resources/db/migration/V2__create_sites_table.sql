-- Migration: V2__create_sites_table.sql
-- Description: Create sites table with BCrypt-hashed authentication credentials
-- Author: Data Forge Team
-- Date: 2025-11-08
-- Consolidated from: V2, V7
--
-- NOTE: Added FK constraint for target_site_id in admin_action_logs
-- (was missing in original migration)

CREATE TABLE sites (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    domain VARCHAR(255) NOT NULL UNIQUE,
    client_secret_hash VARCHAR(60) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for performance optimization and foreign key queries
CREATE INDEX idx_sites_account_id ON sites(account_id);
CREATE INDEX idx_sites_domain ON sites(domain);
CREATE INDEX idx_sites_is_active ON sites(is_active);

-- Comments for documentation
COMMENT ON TABLE sites IS 'Physical or logical data sources (branches, stores) that upload files';
COMMENT ON COLUMN sites.id IS 'Unique site identifier (UUID)';
COMMENT ON COLUMN sites.account_id IS 'Owner account reference';
COMMENT ON COLUMN sites.domain IS 'Unique domain name (used in Basic Auth and S3 path)';
COMMENT ON COLUMN sites.client_secret_hash IS 'BCrypt hashed client secret for authentication (60 character BCrypt hash)';
COMMENT ON COLUMN sites.display_name IS 'Human-readable site name';
COMMENT ON COLUMN sites.is_active IS 'Soft delete flag (false = deactivated)';
COMMENT ON COLUMN sites.created_at IS 'Record creation timestamp';
COMMENT ON COLUMN sites.updated_at IS 'Last modification timestamp';

-- Add foreign key constraint to admin_action_logs.target_site_id
-- (This was created in V14 but needs to be here after sites table exists)
ALTER TABLE admin_action_logs
ADD CONSTRAINT fk_target_site FOREIGN KEY (target_site_id) REFERENCES sites(id) ON DELETE CASCADE;

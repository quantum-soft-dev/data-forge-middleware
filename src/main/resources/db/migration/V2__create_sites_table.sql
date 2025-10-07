-- Migration: V2__create_sites_table.sql
-- Description: Create sites table with authentication credentials
-- Author: Data Forge Team
-- Date: 2025-10-06

CREATE TABLE sites (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    domain VARCHAR(255) NOT NULL UNIQUE,
    client_secret VARCHAR(255) NOT NULL,
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
COMMENT ON COLUMN sites.client_secret IS 'Authentication secret (UUID, auto-generated)';
COMMENT ON COLUMN sites.display_name IS 'Human-readable site name';
COMMENT ON COLUMN sites.is_active IS 'Soft delete flag (false = deactivated)';
COMMENT ON COLUMN sites.created_at IS 'Record creation timestamp';
COMMENT ON COLUMN sites.updated_at IS 'Last modification timestamp';

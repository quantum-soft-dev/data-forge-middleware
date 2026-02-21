-- V28: Add site types and site schemas for CDC support
-- CR: Site Types & Postgres CDC Support

-- 1. Add site_type to sites (DBF default for backward compatibility)
ALTER TABLE sites ADD COLUMN site_type VARCHAR(20) NOT NULL DEFAULT 'DBF';
CREATE INDEX idx_sites_site_type ON sites(site_type);

-- 2. Add site_type to device_authorizations
ALTER TABLE device_authorizations ADD COLUMN site_type VARCHAR(20) NOT NULL DEFAULT 'DBF';

-- 3. Create site_schemas table (one schema per site, JSONB storage)
CREATE TABLE site_schemas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id UUID NOT NULL REFERENCES sites(id) ON DELETE CASCADE,
    schema_data JSONB NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_site_schemas_site_id UNIQUE (site_id)
);

CREATE INDEX idx_site_schemas_site_id ON site_schemas(site_id);

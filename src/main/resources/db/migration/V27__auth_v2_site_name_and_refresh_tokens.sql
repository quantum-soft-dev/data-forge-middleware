-- Migration: V27__auth_v2_site_name_and_refresh_tokens.sql
-- Description: Auth V2 - Add site_name to sites, create refresh_tokens table
-- Author: Data Forge Team
-- Date: 2026-02-10
--
-- This migration supports the Auth V2 changes:
-- 1. Adds site_name column (extracted from composite domain)
-- 2. Creates composite unique key (account_id, site_name)
-- 3. Creates refresh_tokens table for JWT refresh token rotation
-- 4. Keeps existing domain and client_secret_hash for backward compatibility

-- ============================================================
-- 1. Add site_name column to sites
-- ============================================================
ALTER TABLE sites ADD COLUMN site_name VARCHAR(255);

-- 2. Populate site_name from composite domain (skip UUID prefix + underscore = 37 chars)
UPDATE sites SET site_name = SUBSTRING(domain FROM 38);

-- 3. Make site_name NOT NULL
ALTER TABLE sites ALTER COLUMN site_name SET NOT NULL;

-- 4. Create composite unique constraint (account_id, site_name)
ALTER TABLE sites ADD CONSTRAINT uk_sites_account_site_name
    UNIQUE (account_id, site_name);

-- 5. Index for site_name lookups
CREATE INDEX idx_sites_site_name ON sites(site_name);

COMMENT ON COLUMN sites.site_name IS 'Clean site name without account prefix (Unicode allowed)';

-- ============================================================
-- 6. Create refresh_tokens table
-- ============================================================
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id UUID NOT NULL REFERENCES sites(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP,
    CONSTRAINT uk_refresh_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_site_id ON refresh_tokens(site_id);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens(expires_at);
CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);

COMMENT ON TABLE refresh_tokens IS 'JWT refresh tokens for device authentication (Auth V2)';
COMMENT ON COLUMN refresh_tokens.token_hash IS 'SHA-256 hash of the opaque refresh token';
COMMENT ON COLUMN refresh_tokens.expires_at IS 'Token expiration (90 days from creation)';
COMMENT ON COLUMN refresh_tokens.revoked_at IS 'When token was revoked (NULL if active)';

-- ============================================================
-- 7. Make client_secret_hash nullable (no longer required for new sites)
-- ============================================================
ALTER TABLE sites ALTER COLUMN client_secret_hash DROP NOT NULL;

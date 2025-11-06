-- V17: Rename keycloak_user_id to identity_provider_user_id for Auth0 migration
-- Feature: 011-auth0-migration-migrate
-- Date: 2025-11-06
-- Purpose: Generic identity provider column to support Auth0 migration

-- Drop existing index
DROP INDEX IF EXISTS idx_accounts_keycloak_user_id;

-- Rename column
ALTER TABLE accounts
RENAME COLUMN keycloak_user_id TO identity_provider_user_id;

-- Expand column length from 36 to 64 to accommodate Auth0 user IDs (format: auth0|{alphanumeric})
ALTER TABLE accounts
ALTER COLUMN identity_provider_user_id TYPE VARCHAR(64);

-- Recreate unique constraint with new name
ALTER TABLE accounts
DROP CONSTRAINT IF EXISTS uq_keycloak_user_id;

ALTER TABLE accounts
ADD CONSTRAINT uq_identity_provider_user_id UNIQUE (identity_provider_user_id);

-- Create new index with new name
CREATE UNIQUE INDEX idx_accounts_identity_provider_user_id
ON accounts(identity_provider_user_id)
WHERE identity_provider_user_id IS NOT NULL;

-- Update comment
COMMENT ON COLUMN accounts.identity_provider_user_id IS 'Identity provider user ID (Keycloak UUID or Auth0 user ID) for authentication (nullable for backwards compatibility)';

-- V7: Hash client_secret with BCrypt
--
-- Changes:
-- 1. Rename column client_secret to client_secret_hash
-- 2. Increase column length to 60 (BCrypt hash length)
-- 3. Update existing data (NOTE: Existing secrets will be invalid after migration)
--
-- IMPORTANT: After this migration, all existing client secrets will need to be regenerated
-- and provided to site owners, as the plaintext secrets cannot be recovered from UUIDs.

-- Rename column and adjust length for BCrypt hash (60 characters)
ALTER TABLE sites
    RENAME COLUMN client_secret TO client_secret_hash;

ALTER TABLE sites
    ALTER COLUMN client_secret_hash TYPE VARCHAR(60);

-- Add comment
COMMENT ON COLUMN sites.client_secret_hash IS 'BCrypt hashed client secret for authentication';

-- NOTE: Existing UUID-based secrets in the database will no longer work.
-- Admins must regenerate secrets for all sites via the admin API after this migration.

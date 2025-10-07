-- Migration: V1__create_accounts_table.sql
-- Description: Create accounts table with soft delete support
-- Author: Data Forge Team
-- Date: 2025-10-06

CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for performance optimization
CREATE INDEX idx_accounts_email ON accounts(email);
CREATE INDEX idx_accounts_is_active ON accounts(is_active);

-- Comments for documentation
COMMENT ON TABLE accounts IS 'User accounts that manage multiple data source sites';
COMMENT ON COLUMN accounts.id IS 'Unique account identifier (UUID)';
COMMENT ON COLUMN accounts.email IS 'User email address (unique, used for login)';
COMMENT ON COLUMN accounts.name IS 'User display name';
COMMENT ON COLUMN accounts.is_active IS 'Soft delete flag (false = deactivated)';
COMMENT ON COLUMN accounts.created_at IS 'Record creation timestamp';
COMMENT ON COLUMN accounts.updated_at IS 'Last modification timestamp';

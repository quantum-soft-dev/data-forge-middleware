-- Migration: V1__create_accounts_and_admin_logs_tables.sql
-- Description: Create accounts table with Auth0 integration and admin action audit logs
-- Author: Data Forge Team
-- Date: 2025-11-08
-- Consolidated from: V1, V9, V10, V11, V12, V13, V14, V17

-- ============================================================
-- ACCOUNTS TABLE
-- ============================================================

CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    phone VARCHAR(50),
    company VARCHAR(255),
    identity_provider_user_id VARCHAR(64),
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_identity_provider_user_id UNIQUE (identity_provider_user_id)
);

-- Indexes for performance optimization
CREATE INDEX idx_accounts_email ON accounts(email);
CREATE INDEX idx_accounts_is_active ON accounts(is_active);

-- Partial indexes for optional fields (only index non-null values)
CREATE INDEX idx_accounts_phone ON accounts(phone) WHERE phone IS NOT NULL;
CREATE INDEX idx_accounts_company ON accounts(company) WHERE company IS NOT NULL;

-- Unique partial index for identity provider user ID (only index non-null values)
CREATE UNIQUE INDEX idx_accounts_identity_provider_user_id
    ON accounts(identity_provider_user_id)
    WHERE identity_provider_user_id IS NOT NULL;

-- Comments for documentation
COMMENT ON TABLE accounts IS 'User accounts that manage multiple data source sites';
COMMENT ON COLUMN accounts.id IS 'Unique account identifier (UUID)';
COMMENT ON COLUMN accounts.email IS 'User email address (unique, used for login)';
COMMENT ON COLUMN accounts.name IS 'User display name';
COMMENT ON COLUMN accounts.phone IS 'Account phone number (optional)';
COMMENT ON COLUMN accounts.company IS 'Account company name (optional)';
COMMENT ON COLUMN accounts.identity_provider_user_id IS 'Identity provider user ID (Auth0 format: auth0|{id}) for authentication (nullable for backwards compatibility)';
COMMENT ON COLUMN accounts.is_active IS 'Soft delete flag (false = deactivated)';
COMMENT ON COLUMN accounts.created_at IS 'Record creation timestamp';
COMMENT ON COLUMN accounts.updated_at IS 'Last modification timestamp';

-- ============================================================
-- ADMIN ACTION LOGS TABLE
-- ============================================================

CREATE TABLE admin_action_logs (
    id BIGSERIAL PRIMARY KEY,
    action_type VARCHAR(50) NOT NULL,
    target_account_id UUID,
    target_site_id UUID,
    admin_account_id UUID,
    status VARCHAR(20) NOT NULL,
    error_message TEXT,
    ip_address VARCHAR(45),
    user_agent TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_target_account FOREIGN KEY (target_account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    CONSTRAINT fk_admin_account FOREIGN KEY (admin_account_id) REFERENCES accounts(id) ON DELETE RESTRICT,
    CONSTRAINT chk_action_type CHECK (
        action_type IN (
            'CREATE_ACCOUNT', 'LOCK_ACCOUNT', 'UNLOCK_ACCOUNT', 'RESET_PASSWORD',
            'CREATE_SITE', 'DEACTIVATE_SITE', 'ACTIVATE_SITE', 'DELETE_SITE'
        )
    ),
    CONSTRAINT chk_status CHECK (status IN ('SUCCESS', 'FAILED')),
    CONSTRAINT chk_error_message CHECK (
        (status = 'SUCCESS' AND error_message IS NULL) OR
        (status = 'FAILED' AND error_message IS NOT NULL)
    )
);

-- Indexes for audit logs
CREATE INDEX idx_audit_target_account ON admin_action_logs(target_account_id);
CREATE INDEX idx_audit_target_site ON admin_action_logs(target_site_id);
CREATE INDEX idx_audit_admin_account ON admin_action_logs(admin_account_id);
CREATE INDEX idx_audit_action_type ON admin_action_logs(action_type);
CREATE INDEX idx_audit_created_at ON admin_action_logs(created_at DESC);

-- Comments for documentation
COMMENT ON TABLE admin_action_logs IS 'Audit trail for administrative account and site management actions';
COMMENT ON COLUMN admin_action_logs.target_account_id IS 'Target account ID. NULL allowed for CREATE_ACCOUNT failures where account was not created.';
COMMENT ON COLUMN admin_action_logs.target_site_id IS 'Target site ID. NULL for account-level actions, populated for site-specific actions.';
COMMENT ON COLUMN admin_action_logs.admin_account_id IS 'Admin account ID who performed the action. ALWAYS NULL - admins are Auth0 users with ROLE_ADMIN but have no Account record. Admin identity tracked via Auth0 JWT (email, sub claims).';
COMMENT ON COLUMN admin_action_logs.status IS 'Action outcome: SUCCESS or FAILED';
COMMENT ON COLUMN admin_action_logs.error_message IS 'Error details for failed actions (required when status = FAILED)';

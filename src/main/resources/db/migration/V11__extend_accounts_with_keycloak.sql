-- V11: Extend accounts table with Keycloak integration
-- Feature: 006-task-the-admin
-- Date: 2025-10-28
-- Purpose: Add Keycloak user ID correlation and admin action audit logging

-- Add Keycloak integration to existing accounts table
ALTER TABLE accounts
ADD COLUMN keycloak_user_id VARCHAR(36);

-- Add unique constraint
ALTER TABLE accounts
ADD CONSTRAINT uq_keycloak_user_id UNIQUE (keycloak_user_id);

-- Create index for lookups
CREATE INDEX idx_accounts_keycloak_user_id ON accounts(keycloak_user_id);

-- Comment
COMMENT ON COLUMN accounts.keycloak_user_id IS 'Keycloak user UUID for authentication (nullable for backwards compatibility)';

-- Create admin_action_logs table
CREATE TABLE admin_action_logs (
    id BIGSERIAL PRIMARY KEY,
    action_type VARCHAR(50) NOT NULL,
    target_account_id UUID NOT NULL,
    admin_account_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_message TEXT,
    ip_address VARCHAR(45),
    user_agent TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_target_account FOREIGN KEY (target_account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    CONSTRAINT fk_admin_account FOREIGN KEY (admin_account_id) REFERENCES accounts(id) ON DELETE RESTRICT,
    CONSTRAINT chk_action_type CHECK (action_type IN ('CREATE_ACCOUNT', 'LOCK_ACCOUNT', 'UNLOCK_ACCOUNT', 'RESET_PASSWORD')),
    CONSTRAINT chk_status CHECK (status IN ('SUCCESS', 'FAILED')),
    CONSTRAINT chk_error_message CHECK (
        (status = 'SUCCESS' AND error_message IS NULL) OR
        (status = 'FAILED' AND error_message IS NOT NULL)
    )
);

-- Indexes for audit logs
CREATE INDEX idx_audit_target_account ON admin_action_logs(target_account_id);
CREATE INDEX idx_audit_admin_account ON admin_action_logs(admin_account_id);
CREATE INDEX idx_audit_action_type ON admin_action_logs(action_type);
CREATE INDEX idx_audit_created_at ON admin_action_logs(created_at DESC);

-- Comments
COMMENT ON TABLE admin_action_logs IS 'Audit trail for administrative account management actions';

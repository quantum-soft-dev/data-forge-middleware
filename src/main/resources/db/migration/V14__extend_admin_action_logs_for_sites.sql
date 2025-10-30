-- V14: Extend admin_action_logs for site management
-- Feature: 007-adding-a-site
-- Date: 2025-10-30
-- Purpose: Add site-specific action types and target_site_id column

-- Add target_site_id column for site-related admin actions
ALTER TABLE admin_action_logs
ADD COLUMN target_site_id UUID;

-- Add foreign key constraint to sites table
ALTER TABLE admin_action_logs
ADD CONSTRAINT fk_target_site FOREIGN KEY (target_site_id) REFERENCES sites(id) ON DELETE SET NULL;

-- Create index for site-related queries
CREATE INDEX idx_admin_logs_target_site ON admin_action_logs(target_site_id, created_at DESC);

-- Drop existing CHECK constraint on action_type
ALTER TABLE admin_action_logs
DROP CONSTRAINT chk_action_type;

-- Create new CHECK constraint with site action types
ALTER TABLE admin_action_logs
ADD CONSTRAINT chk_action_type CHECK (action_type IN (
    'CREATE_ACCOUNT',
    'LOCK_ACCOUNT',
    'UNLOCK_ACCOUNT',
    'RESET_PASSWORD',
    'CREATE_SITE',
    'DEACTIVATE_SITE',
    'ACTIVATE_SITE',
    'DELETE_SITE'
));

-- Add composite unique constraint to sites table for domain uniqueness per account
-- (This is recommended if not already present)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uk_sites_account_domain'
    ) THEN
        ALTER TABLE sites ADD CONSTRAINT uk_sites_account_domain UNIQUE (account_id, domain);
    END IF;
END $$;

-- Add index on is_active for filtering (if not already present)
CREATE INDEX IF NOT EXISTS idx_sites_is_active ON sites(is_active);

-- Update comments
COMMENT ON COLUMN admin_action_logs.target_site_id IS 'Site affected by this action (nullable for account-level actions)';
COMMENT ON COLUMN admin_action_logs.action_type IS 'Type of admin action: CREATE_ACCOUNT, LOCK_ACCOUNT, UNLOCK_ACCOUNT, RESET_PASSWORD, CREATE_SITE, DEACTIVATE_SITE, ACTIVATE_SITE, DELETE_SITE';

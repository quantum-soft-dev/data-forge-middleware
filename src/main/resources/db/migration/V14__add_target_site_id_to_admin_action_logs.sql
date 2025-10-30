-- V14: Add target_site_id to admin_action_logs
--
-- Reason: Support tracking administrative actions on sites in addition to accounts.
-- This allows logging site creation, update, deletion, and other site-level admin operations.

ALTER TABLE admin_action_logs
ADD COLUMN target_site_id UUID;

-- Add foreign key constraint
ALTER TABLE admin_action_logs
ADD CONSTRAINT fk_target_site FOREIGN KEY (target_site_id) REFERENCES sites(id) ON DELETE CASCADE;

-- Create index for site-based queries
CREATE INDEX idx_audit_target_site ON admin_action_logs(target_site_id);

-- Add comment
COMMENT ON COLUMN admin_action_logs.target_site_id IS
'Target site ID. NULL for account-level actions, populated for site-specific actions.';

-- Update action_type check constraint to include site-related actions
ALTER TABLE admin_action_logs
DROP CONSTRAINT chk_action_type;

ALTER TABLE admin_action_logs
ADD CONSTRAINT chk_action_type CHECK (
    action_type IN (
        'CREATE_ACCOUNT', 'LOCK_ACCOUNT', 'UNLOCK_ACCOUNT', 'RESET_PASSWORD',
        'CREATE_SITE', 'DEACTIVATE_SITE', 'ACTIVATE_SITE', 'DELETE_SITE'
    )
);

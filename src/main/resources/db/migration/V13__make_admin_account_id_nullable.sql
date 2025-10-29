-- V13: Make admin_account_id nullable in admin_action_logs
--
-- Reason: The admin_account_id is extracted from Keycloak JWT token, but there's currently
-- no mapping between Keycloak user IDs and local account IDs. Making it nullable allows
-- audit logging to continue while we implement proper admin account resolution.

ALTER TABLE admin_action_logs
ALTER COLUMN admin_account_id DROP NOT NULL;

COMMENT ON COLUMN admin_action_logs.admin_account_id IS
'Admin account ID who performed the action. NULL when admin account mapping is not available.';

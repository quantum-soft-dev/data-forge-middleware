-- V12: Make target_account_id nullable in admin_action_logs
--
-- Reason: When logging CREATE_ACCOUNT failures, the account doesn't exist yet,
-- so we can't reference it. The service generates a random UUID placeholder
-- which violates the FK constraint. Making it nullable allows failure logging
-- before account creation.

ALTER TABLE admin_action_logs
ALTER COLUMN target_account_id DROP NOT NULL;

-- Update the FK constraint to handle NULLs gracefully
-- (The existing constraint already has ON DELETE CASCADE which is fine)

COMMENT ON COLUMN admin_action_logs.target_account_id IS
'Target account ID. NULL allowed for CREATE_ACCOUNT failures where account was not created.';

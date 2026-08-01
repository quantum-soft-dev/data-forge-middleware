-- Migration: V50__batch_parquet_queue_operations.sql
-- Description: Allow the audited operator recovery introduced by feature 039 / issue #98.
-- The constraint set is widened only; existing admin audit rows and artifact rows are unchanged.

ALTER TABLE admin_action_logs
    DROP CONSTRAINT IF EXISTS chk_action_type;

ALTER TABLE admin_action_logs
    ADD CONSTRAINT chk_action_type CHECK (
        action_type IN (
            'CREATE_ACCOUNT', 'LOCK_ACCOUNT', 'UNLOCK_ACCOUNT', 'RESET_PASSWORD',
            'CREATE_SITE', 'DEACTIVATE_SITE', 'ACTIVATE_SITE', 'DELETE_SITE',
            'UPDATE_SITE_RETENTION',
            'BATCH_CLEANUP',
            'SITE_HISTORY_WIPE',
            'BATCH_PARQUET_REQUEUE'
        )
    );

COMMENT ON CONSTRAINT chk_action_type ON admin_action_logs IS
    'Allowed AdminActionType values, including audited batch Parquet queue recovery.';

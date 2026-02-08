-- Migration: V24__add_batch_cleanup_action_type.sql
-- Description: Allow BATCH_CLEANUP in admin_action_logs
-- Author: Data Forge Team
-- Date: 2026-02-07

ALTER TABLE admin_action_logs
    DROP CONSTRAINT IF EXISTS chk_action_type;

ALTER TABLE admin_action_logs
    ADD CONSTRAINT chk_action_type CHECK (
        action_type IN (
            'CREATE_ACCOUNT', 'LOCK_ACCOUNT', 'UNLOCK_ACCOUNT', 'RESET_PASSWORD',
            'CREATE_SITE', 'DEACTIVATE_SITE', 'ACTIVATE_SITE', 'DELETE_SITE',
            'UPDATE_SITE_RETENTION',
            'BATCH_CLEANUP'
        )
    );


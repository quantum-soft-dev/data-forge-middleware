-- Migration: V23__add_update_site_retention_action_type.sql
-- Description: Allow UPDATE_SITE_RETENTION in admin_action_logs
-- Author: Data Forge Team
-- Date: 2026-02-05

ALTER TABLE admin_action_logs
    DROP CONSTRAINT IF EXISTS chk_action_type;

ALTER TABLE admin_action_logs
    ADD CONSTRAINT chk_action_type CHECK (
        action_type IN (
            'CREATE_ACCOUNT', 'LOCK_ACCOUNT', 'UNLOCK_ACCOUNT', 'RESET_PASSWORD',
            'CREATE_SITE', 'DEACTIVATE_SITE', 'ACTIVATE_SITE', 'DELETE_SITE',
            'UPDATE_SITE_RETENTION'
        )
    );

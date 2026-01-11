-- V19: Add REINIT and related action types to plugin_audit_logs constraint
-- Feature: 015-plugin-reinit
-- Date: 2026-01-11
-- Issue: REINIT, PLUGIN_HISTORY_CLEARED, SQL_REGENERATION_* types not in check constraint

-- =============================================================================
-- Drop and recreate the check constraint with all action types from PluginActionType enum
-- =============================================================================
ALTER TABLE plugin_audit_logs
    DROP CONSTRAINT IF EXISTS chk_plugin_audit_logs_action_type;

ALTER TABLE plugin_audit_logs
    ADD CONSTRAINT chk_plugin_audit_logs_action_type CHECK (
        action_type IN (
            'ACTIVATE',
            'DEACTIVATE',
            'REACTIVATE',
            'EVENT_DISPATCHED',
            'EVENT_FAILED',
            'EVENT_TIMEOUT',
            'SQL_GENERATION_STARTED',
            'SQL_GENERATION_COMPLETED',
            'SQL_GENERATION_FAILED',
            'PLUGIN_HISTORY_CLEARED',
            'SQL_REGENERATION_STARTED',
            'SQL_REGENERATION_COMPLETED',
            'SQL_REGENERATION_FAILED',
            'REINIT'
        )
    );

-- Update comment to reflect all action types
COMMENT ON COLUMN plugin_audit_logs.action_type IS
    'Type of operation: ACTIVATE, DEACTIVATE, REACTIVATE, EVENT_*, SQL_GENERATION_*, SQL_REGENERATION_*, PLUGIN_HISTORY_CLEARED, REINIT';

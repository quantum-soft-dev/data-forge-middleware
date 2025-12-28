-- V10: Add SQL_GENERATION action types to plugin_audit_logs
-- Feature: 001-plugin-sql-generation
-- Date: 2025-12-28
-- Issue: SQL_GENERATION_STARTED, SQL_GENERATION_COMPLETED, SQL_GENERATION_FAILED
--        were not included in the original check constraint

-- =============================================================================
-- Drop and recreate the check constraint with new action types
-- =============================================================================
ALTER TABLE plugin_audit_logs
    DROP CONSTRAINT chk_plugin_audit_logs_action_type;

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
            'SQL_GENERATION_FAILED'
        )
    );

-- Update comment to reflect new action types
COMMENT ON COLUMN plugin_audit_logs.action_type IS 'Type of operation: ACTIVATE, DEACTIVATE, REACTIVATE, EVENT_DISPATCHED, EVENT_FAILED, EVENT_TIMEOUT, SQL_GENERATION_STARTED, SQL_GENERATION_COMPLETED, SQL_GENERATION_FAILED';

-- V45: Allow the API_KEY_ROTATED plugin audit action type
-- Issue: #66 — Bit BI API key rotation over HTTP
-- Date: 2026-07-28
--
-- Keeps chk_plugin_audit_logs_action_type in sync with PluginActionType (see V44 for why the
-- two drifted apart). PluginAuditLogActionTypeIntegrationTest fails without this migration.

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
            'SQL_GENERATION_DELETED',
            'PLUGIN_HISTORY_CLEARED',
            'SQL_REGENERATION_STARTED',
            'SQL_REGENERATION_COMPLETED',
            'SQL_REGENERATION_FAILED',
            'REINIT',
            'FILES_LISTED',
            'LINK_CONSUMED',
            'LINK_REJECTED',
            'PASSWORD_ROTATED',
            'API_KEY_ROTATED'
        )
    );

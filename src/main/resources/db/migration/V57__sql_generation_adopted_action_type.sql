-- V57: SQL_GENERATION_ADOPTED joins the plugin audit action types (issue #260).
-- Same NOT VALID + VALIDATE split as V44 / V48: plugin_audit_logs is partitioned with
-- indefinite retention, so a validating ADD CONSTRAINT would hold ACCESS EXCLUSIVE on the
-- parent and every partition while it scans all retained rows. The new set is a strict
-- superset of the old one, so validation cannot fail.
--
-- SQL_REGENERATION_* stay: they are stored-data values a historical audit row may carry
-- (issue #190 retired the writers, not the enum).
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
            'SQL_GENERATION_ADOPTED',
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
            'API_KEY_ROTATED',
            'DELTA_AUTO_REINIT'
        )
    ) NOT VALID;

ALTER TABLE plugin_audit_logs
    VALIDATE CONSTRAINT chk_plugin_audit_logs_action_type;

COMMENT ON COLUMN plugin_audit_logs.action_type IS
    'Type of operation: ACTIVATE, DEACTIVATE, REACTIVATE, EVENT_*, SQL_GENERATION_*, '
    'SQL_REGENERATION_*, PLUGIN_HISTORY_CLEARED, REINIT, DELTA_AUTO_REINIT, the Parquet Export '
    'types FILES_LISTED, LINK_CONSUMED, LINK_REJECTED, PASSWORD_ROTATED, and API_KEY_ROTATED. '
    'SQL_GENERATION_ADOPTED is the terminal for a lost unique claim (issue #260). Must stay in '
    'sync with the PluginActionType enum — see PluginAuditLogActionTypeIntegrationTest.';

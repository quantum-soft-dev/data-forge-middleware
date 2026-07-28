-- V44: Re-sync chk_plugin_audit_logs_action_type with the PluginActionType enum
-- Date: 2026-07-28
--
-- The constraint was last updated by V19 (feature 015). Six values are missing from it:
--
--   SQL_GENERATION_DELETED  -- declared but not written by any production path
--   FILES_LISTED            -- feature 028, written by ParquetExportApiController
--   LINK_CONSUMED           -- feature 028, written by DownloadLinkService
--   LINK_REJECTED           -- feature 028, written by DownloadLinkService
--   PASSWORD_ROTATED        -- feature 028, written by ParquetExportCredentialsService
--   API_KEY_ROTATED         -- issue #66, Bit BI API key rotation
--
-- Every audit write in PluginAuditService is wrapped in a catch-all so that a failing audit can
-- never break the operation it records. The four Parquet Export types were therefore rejected by
-- this constraint and swallowed: feature 028 has had no audit trail at all since it merged.
-- PluginAuditLogActionTypeIntegrationTest now fails on any future drift.
--
-- Lock behaviour: plugin_audit_logs is partitioned and has indefinite retention, so a validating
-- ADD CONSTRAINT would hold ACCESS EXCLUSIVE on the parent and every partition while it scans all
-- retained rows. Long enough, and the async audit writers this migration exists to unblock would
-- time out — recreating the gap. Instead the constraint is added NOT VALID (a catalogue-only
-- change that still enforces every new row) and validated in a second step, which takes only
-- SHARE UPDATE EXCLUSIVE and does not block inserts.
--
-- The new set is a strict superset of the old one, so validation cannot fail: any row that
-- satisfied the previous constraint satisfies this one.
--
-- All six values ship in this single migration on purpose. Splitting them would mean validating
-- the whole table twice for one logical change.

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
    ) NOT VALID;

ALTER TABLE plugin_audit_logs
    VALIDATE CONSTRAINT chk_plugin_audit_logs_action_type;

COMMENT ON COLUMN plugin_audit_logs.action_type IS
    'Type of operation: ACTIVATE, DEACTIVATE, REACTIVATE, EVENT_*, SQL_GENERATION_*, '
    'SQL_REGENERATION_*, PLUGIN_HISTORY_CLEARED, REINIT, the Parquet Export types FILES_LISTED, '
    'LINK_CONSUMED, LINK_REJECTED, PASSWORD_ROTATED, and API_KEY_ROTATED. Must stay in sync with '
    'the PluginActionType enum — see PluginAuditLogActionTypeIntegrationTest.';

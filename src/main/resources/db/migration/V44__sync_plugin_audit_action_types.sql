-- V44: Re-sync chk_plugin_audit_logs_action_type with the PluginActionType enum
-- Date: 2026-07-28
--
-- The constraint was last updated by V19 (feature 015). Five values have been added to
-- PluginActionType since then without a matching migration:
--
--   SQL_GENERATION_DELETED  -- declared but not yet written by any production path
--   FILES_LISTED            -- feature 028, written by ParquetExportApiController
--   LINK_CONSUMED           -- feature 028, written by DownloadLinkService
--   LINK_REJECTED           -- feature 028, written by DownloadLinkService
--   PASSWORD_ROTATED        -- feature 028, written by ParquetExportCredentialsService
--
-- Every audit write in PluginAuditService is wrapped in a catch-all so that a failing audit
-- can never break the operation it records. The four Parquet Export types were therefore
-- rejected by this constraint and swallowed: feature 028 has had no audit trail at all since
-- it merged. PluginAuditLogActionTypeIntegrationTest now fails on any future drift.

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
            'PASSWORD_ROTATED'
        )
    );

COMMENT ON COLUMN plugin_audit_logs.action_type IS
    'Type of operation: ACTIVATE, DEACTIVATE, REACTIVATE, EVENT_*, SQL_GENERATION_*, '
    'SQL_REGENERATION_*, PLUGIN_HISTORY_CLEARED, REINIT, and the Parquet Export types '
    'FILES_LISTED, LINK_CONSUMED, LINK_REJECTED, PASSWORD_ROTATED. Must stay in sync with '
    'the PluginActionType enum — see PluginAuditLogActionTypeIntegrationTest.';

-- Migration: V48__site_history_wipe.sql
-- Description: Site history wipe — a true clean slate for one site (issue #89, feature 035).
--   1. site_sync_state.generation   — epoch counter bumped by a wipe and by nothing else. The
--      Delta v2 client persists the last generation it saw and, on any change, drops its local
--      journal and resets its seq counter to zero. NEED_REBASELINE alone cannot express that: it
--      says "send a full snapshot", not "your counters are meaningless now".
--   2. site_sync_state.wipe_pending — set by the wipe, consumed by the first checkpoint built
--      afterwards, which is when the Bit BI delta baselines are re-captured automatically.
--   3. admin_action_logs.details    — the wipe reports counts, bytes, the new generation and
--      whether a plugin baseline batch had to be detached. The table had nowhere to put that:
--      error_message is constrained to NULL on SUCCESS.
--   4. CHECK extensions for the two new audit action types.
--
-- All four are additive and backfill-free: DEFAULT 0 / FALSE means every existing site starts at
-- generation 0 with no wipe pending, which is exactly the pre-feature state, and no existing row
-- carries details.
-- Author: Data Forge Team
-- Date: 2026-07-31

ALTER TABLE site_sync_state ADD COLUMN generation BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN site_sync_state.generation IS
    'Epoch counter bumped only by a site history wipe; the client resets its local journal and seq '
    'counter whenever it changes. Monotonic: the row is reset by a wipe, never deleted.';

ALTER TABLE site_sync_state ADD COLUMN wipe_pending BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN site_sync_state.wipe_pending IS
    'A wipe is awaiting its first post-wipe checkpoint, at which point the Bit BI delta baselines '
    'are re-captured automatically (auto-reinit) and the flag is cleared.';

ALTER TABLE admin_action_logs ADD COLUMN details JSONB;

COMMENT ON COLUMN admin_action_logs.details IS
    'Structured outcome of the action (e.g. SITE_HISTORY_WIPE counts, bytes, new generation, '
    'whether a plugin baseline batch was detached). NULL for actions that report nothing.';

-- SITE_HISTORY_WIPE joins the admin action types (V23/V24 pattern: drop + recreate the whole list).
ALTER TABLE admin_action_logs
    DROP CONSTRAINT IF EXISTS chk_action_type;

ALTER TABLE admin_action_logs
    ADD CONSTRAINT chk_action_type CHECK (
        action_type IN (
            'CREATE_ACCOUNT', 'LOCK_ACCOUNT', 'UNLOCK_ACCOUNT', 'RESET_PASSWORD',
            'CREATE_SITE', 'DEACTIVATE_SITE', 'ACTIVATE_SITE', 'DELETE_SITE',
            'UPDATE_SITE_RETENTION',
            'BATCH_CLEANUP',
            'SITE_HISTORY_WIPE'
        )
    );

-- DELTA_AUTO_REINIT joins the plugin audit action types. Same NOT VALID + VALIDATE split as V44:
-- plugin_audit_logs is partitioned with indefinite retention, so a validating ADD CONSTRAINT would
-- hold ACCESS EXCLUSIVE on the parent and every partition while it scans all retained rows. The new
-- set is a strict superset of the old one, so validation cannot fail.
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
            'API_KEY_ROTATED',
            'DELTA_AUTO_REINIT'
        )
    ) NOT VALID;

ALTER TABLE plugin_audit_logs
    VALIDATE CONSTRAINT chk_plugin_audit_logs_action_type;

COMMENT ON COLUMN plugin_audit_logs.action_type IS
    'Type of operation: ACTIVATE, DEACTIVATE, REACTIVATE, EVENT_*, SQL_GENERATION_*, '
    'SQL_REGENERATION_*, PLUGIN_HISTORY_CLEARED, REINIT, DELTA_AUTO_REINIT, the Parquet Export '
    'types FILES_LISTED, LINK_CONSUMED, LINK_REJECTED, PASSWORD_ROTATED, and API_KEY_ROTATED. Must '
    'stay in sync with the PluginActionType enum — see PluginAuditLogActionTypeIntegrationTest.';

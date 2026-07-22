-- V35: Delta Sync UI (feature 023) — persistent rebaseline/rebuild request flags
-- specs/022-delta-client-v2/ui-redesign-tasks.md (B2)

-- rebaseline_requested: set by POST .../delta/rebaseline (owner/admin); GetSyncState answers
-- NEED_REBASELINE while set; cleared when the client actually starts its FULL_SNAPSHOT session.
ALTER TABLE site_sync_state ADD COLUMN rebaseline_requested BOOLEAN NOT NULL DEFAULT FALSE;

-- rebuild_requested: set by POST .../delta/checkpoints/rebuild (admin); cleared when the
-- forced checkpoint rebuild completes.
ALTER TABLE site_sync_state ADD COLUMN rebuild_requested BOOLEAN NOT NULL DEFAULT FALSE;

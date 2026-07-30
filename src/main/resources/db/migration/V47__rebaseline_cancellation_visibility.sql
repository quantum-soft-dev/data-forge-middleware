-- Migration: V47__rebaseline_cancellation_visibility.sql
-- Description: Make a re-baseline cancellation answerable (issue #84 review).
--   1. batches.session_mode      — the Delta v2 session mode of a batch (029: batch = one session),
--      so an open session can be told apart from a running FULL_SNAPSHOT. Previously the mode lived
--      only in the gRPC stream's heap, which forced the cancellation endpoint to treat every open
--      batch (incl. long-lived CONTINUOUS sessions and batches abandoned until the timeout sweeper)
--      as a possible snapshot.
--   2. site_sync_state.rebaseline_notified_at — when GetSyncState first answered NEED_REBASELINE for
--      the pending request. A cancellation before that point provably reaches the client; after it,
--      the client may already be preparing the snapshot.
-- Both columns are nullable with no backfill: existing batches predate v2 mode tracking, and a
-- pending request that was already answered is re-stamped on the client's next poll.
-- Author: Data Forge Team
-- Date: 2026-07-30

ALTER TABLE batches ADD COLUMN session_mode VARCHAR(20);

COMMENT ON COLUMN batches.session_mode IS
    'Delta v2 session mode of this batch (FULL_SNAPSHOT, DELTA, CONTINUOUS); NULL for batches started without one';

ALTER TABLE site_sync_state ADD COLUMN rebaseline_notified_at TIMESTAMP;

COMMENT ON COLUMN site_sync_state.rebaseline_notified_at IS
    'When GetSyncState first answered NEED_REBASELINE for the pending request; cleared with the flag';

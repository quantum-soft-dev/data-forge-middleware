-- V61: a batch keeps the baseline epoch its records belong to (issue #388)
--
-- The Bit BI v2 changes feed lists a site's batches of its current epoch, and the epoch is a fact
-- about the site (site_sync_state.baseline_epoch) that moves on: a history wipe deletes every batch
-- and bumps it, and a re-baseline — every committed FULL_SNAPSHOT runs DeltaRebaselineService.reset
-- — bumps it while the older batches stay. So the epoch a batch belongs to is stored with the batch,
-- written by BatchRepository#storeBaselineEpoch in the transaction that records each of its
-- segments, after the reset: a FULL_SNAPSHOT carries the epoch it opened.
--
-- NULL means "not stamped": a batch that recorded no segment, one committed before V61 outside the
-- epoch backfilled below, or one committed by a pre-V61 pod during the rolling deploy. The feed reads
-- a NULL by the rule the backfill uses, so a batch committed in that window is not lost.
--
-- No default, on purpose: any default would stamp an epoch on rows an old pod inserts.

ALTER TABLE batches
    ADD COLUMN baseline_epoch BIGINT;

COMMENT ON COLUMN batches.baseline_epoch IS
    'site_sync_state.baseline_epoch the batch''s records belong to, written with each recorded segment '
    '(a FULL_SNAPSHOT: the epoch its reset opened). NULL = not stamped (issue #388).';

-- Both indexes are built before the backfill: it runs under the ACCESS EXCLUSIVE lock the ALTER TABLE
-- took, and with idx_batches_site_completed_snapshot each of its NOT EXISTS probes is one lookup.

-- The feed's seq predicate (last_seq > afterSeq) within one site: a poll from a watermark reads the
-- few batches above it instead of walking the epoch from its start (measured on 3000 batches:
-- 7 buffers against a sequential scan of 273). The cost is that a seal's update of last_seq is no
-- longer a HOT update — one index entry per seal.
CREATE INDEX idx_batches_site_last_seq
    ON batches (site_id, last_seq)
    WHERE last_seq IS NOT NULL;

-- The boundary of a site's current epoch: its latest completed FULL_SNAPSHOT. The feed reads it once
-- per request and starts its walk there, so a first read never touches the discarded epochs before
-- it. One entry per re-baseline, written when the snapshot completes.
CREATE INDEX idx_batches_site_completed_snapshot
    ON batches (site_id, started_at DESC, id DESC)
    WHERE session_mode = 'FULL_SNAPSHOT' AND status IN ('COMPLETED', 'COMPLETED_WITH_WARNINGS');

-- Backfill the current epoch only. A batch belongs to it when no completed FULL_SNAPSHOT of its site
-- started after it: each such snapshot moved the epoch once, and a wipe leaves no older batch behind.
-- Older batches stay NULL — they belong to a discarded history, which the same rule tells apart, and
-- their exact epoch numbers cannot be reconstructed for snapshots committed before V52 introduced
-- baseline_epoch. A batch still IN_PROGRESS is left NULL: if it is a FULL_SNAPSHOT its records will
-- belong to the epoch its own reset opens, not to today's.
UPDATE batches b
   SET baseline_epoch = s.baseline_epoch
  FROM site_sync_state s
 WHERE s.site_id = b.site_id
   AND b.status <> 'IN_PROGRESS'
   AND b.first_seq IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM batches later
                    WHERE later.site_id = b.site_id
                      AND later.session_mode = 'FULL_SNAPSHOT'
                      AND later.status IN ('COMPLETED', 'COMPLETED_WITH_WARNINGS')
                      AND (later.started_at, later.id) > (b.started_at, b.id));

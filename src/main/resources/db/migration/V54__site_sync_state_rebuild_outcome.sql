-- V54: give a forced checkpoint rebuild a verdict the operator can read (issue #186)
--
-- `POST /api/v1/sites/{siteId}/delta/checkpoints/rebuild` answers 202 and raises
-- `rebuild_requested`, which the UI shows as a "Rebuild queued" chip. The flag is released when the
-- attempt settles — and three of that attempt's four endings ran nothing at all (a failure, an S3
-- read denial on the seed frame per #157, a deferral behind the process's fold budget per #178).
-- All four looked identical from outside the pod: the chip vanished and the checkpoints did not
-- change. The only recourse was to notice nothing had happened and click again, which is what the
-- code's own log line said to do, in a log the operator cannot read.
--
-- Holding the flag instead is not the fix and has been rejected twice (#157 round 2, #178): nothing
-- re-drives a held flag — the nightly tick calls buildCheckpoint, never rebuildFromFrame — and
-- requestRebuild short-circuits while it is set, so a held flag leaves the operator unable even to
-- ask again. So the flag keeps its semantics exactly, and the attempt leaves a verdict beside it.
--
-- All three columns are nullable, and NULL is a meaningful value: "this site has no finished
-- rebuild attempt on record". Every existing row starts there, which is precisely what was true
-- before this migration.
--
-- No CHECK constraint on `last_rebuild_outcome`: the column is written only by
-- CheckpointRebuildOutcome through an EnumType.STRING mapping, so a value outside the enum cannot
-- arrive, while a constraint would make every future outcome a migration of its own.

ALTER TABLE site_sync_state
    ADD COLUMN last_rebuild_outcome    VARCHAR(32),
    ADD COLUMN last_rebuild_outcome_at TIMESTAMP,
    ADD COLUMN last_rebuild_message    VARCHAR(1000);

COMMENT ON COLUMN site_sync_state.last_rebuild_outcome IS
    'How the most recent *finished* forced checkpoint rebuild ended: COMPLETED, FAILED, '
    'FRAME_UNAVAILABLE (S3 would not say whether the seed frame is there — #157) or DEFERRED '
    '(another build held the process fold budget — #178). NULL when the site has never had one. '
    'A rebuild cut short by the process shutting down writes nothing and keeps rebuild_requested, '
    'because it has not finished — the next process re-drives it (#162, issue #186).';
COMMENT ON COLUMN site_sync_state.last_rebuild_outcome_at IS
    'When last_rebuild_outcome was recorded. Read together with rebuild_requested: while that flag '
    'is up, the verdict describes the *previous* attempt, not the queued one.';
COMMENT ON COLUMN site_sync_state.last_rebuild_message IS
    'Operator-facing explanation of last_rebuild_outcome — the failure''s own text, truncated to '
    'the column width by the writer. NULL for COMPLETED, which has nothing to explain.';

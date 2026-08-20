-- V55: give a scheduled checkpoint build that aborted a verdict the UI can read (issue #224)
--
-- Since #213 a site with last_checkpoint_seq = 0 and records applied reads as a neutral
-- "No checkpoint yet" — right for the case it was written for (the first nightly build has
-- not come round yet). A whole-site abort writes no checkpoints row and leaves the pointer
-- at zero, so thirty failed nights carry byte-for-byte the payload of a site ingested this
-- afternoon. next_checkpoint_build_at cannot separate them either: it is the next cron
-- occurrence recomputed per request, so it is always in the future.
--
-- These three columns are the persisted fact that a scheduled visit already passed this
-- site by. They are written only on the abort path (CheckpointScheduler's catch); a healthy
-- build advances last_checkpoint_seq and does not touch them. NULL means "no aborted
-- scheduled build on record", which is what every existing row is.
--
-- No CHECK constraint on last_checkpoint_build_abort: the column is written only by
-- CheckpointBuildAbort through an EnumType.STRING mapping, so a value outside the enum
-- cannot arrive, while a constraint would make every future reason a migration of its own.

ALTER TABLE site_sync_state
    ADD COLUMN last_checkpoint_build_abort    VARCHAR(32),
    ADD COLUMN last_checkpoint_build_abort_at TIMESTAMP,
    ADD COLUMN last_checkpoint_build_message  VARCHAR(1000);

COMMENT ON COLUMN site_sync_state.last_checkpoint_build_abort IS
    'How the most recent scheduled checkpoint build aborted before writing anything: FAILED, '
    'FOLD_TOO_LARGE (the fold outgrew delta.checkpoint.max-fold-bytes — #152), FRAME_TOO_LARGE '
    '(the reload frame crossed delta.checkpoint.max-frame-temp-bytes — #153), SCRATCH_FULL '
    '(the shared Parquet scratch directory was full — #150), FRAME_UNAVAILABLE (S3 would not '
    'say whether the seed frame is there — #157) or DEFERRED (another build held the process '
    'fold budget — #178). NULL when no scheduled build has aborted on record. Written only on '
    'the abort path, and only while last_checkpoint_seq is still 0 (issue #224): once a '
    'checkpoint exists the lag surface already distinguishes a later abort. A wipe and a '
    're-baseline drop it, because both zero the pointer and an abort about the discarded '
    'baseline would then read as "the first build of the new one already failed".';
COMMENT ON COLUMN site_sync_state.last_checkpoint_build_abort_at IS
    'When last_checkpoint_build_abort was recorded.';
COMMENT ON COLUMN site_sync_state.last_checkpoint_build_message IS
    'Operator-facing explanation of last_checkpoint_build_abort — the failure''s own text, '
    'truncated to the column width by the writer. NULL when the outcome needs none.';

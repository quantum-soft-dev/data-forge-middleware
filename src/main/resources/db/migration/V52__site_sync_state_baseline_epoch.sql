-- V52: baseline epoch for the checkpoint build guard (issue #142)
--
-- CheckpointEpochGuard (issue #136) refuses a checkpoint build's writes once the site's history has
-- been replaced under it. It keyed on `generation`, which is the *wire* epoch: it travels to the
-- Delta v2 client in SyncStateResponse/SessionOpened/SessionStart (035) and tells it to drop its
-- journal and reset its seq counter. An ordinary FULL_SNAPSHOT re-baseline must therefore never move
-- it — but a re-baseline deletes every checkpoint row and zeroes last_checkpoint_seq just as a wipe
-- does, so a build that overlapped one passed the guard and restored the pointer of the baseline
-- that had just been discarded.
--
-- baseline_epoch is the second signal: a monotonic counter moved by *anything* that discards the
-- site's checkpoints — a wipe and a re-baseline alike — and never sent to the client. The guard keys
-- on it; `generation` keeps its wire meaning unchanged.
--
-- Backward compatible: existing rows start at 0, which is what a build reading them sees, so no
-- build in flight across the deployment is spuriously refused. The counter only has to be monotonic
-- per site, not comparable with `generation`.
ALTER TABLE site_sync_state
    ADD COLUMN baseline_epoch BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN site_sync_state.baseline_epoch IS
    'Monotonic epoch of the site''s server-side baseline, bumped by a history wipe and by a '
    'FULL_SNAPSHOT re-baseline (issue #142). Server-internal: CheckpointEpochGuard compares it to '
    'refuse the writes of a build whose baseline was replaced mid-flight, and CheckpointRecordedEvent '
    'carries it so a pre-wipe event cannot consume the wipe''s pending-reinit flag. Never sent to the '
    'client — the wire epoch is site_sync_state.generation.';

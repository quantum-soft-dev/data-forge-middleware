-- V53: bound the per-table checkpoint rematerialize retry (issue #149)
--
-- Since #128 a scheduled build retries any checkpoint row whose Parquet snapshot is missing, and
-- since #137 such a row also puts its site on the nightly tick's work list. Nothing bounded that
-- retry, so a table that no build can ever materialize — one with no declared schema the client
-- never submits, or one whose Parquet is deterministically unrenderable — cost a frame download,
-- a whole-site fold and a per-table attempt every night, forever, and delayed the sites with real
-- work behind the scheduler's single lock.
--
-- The counter is the state: a row that has spent `delta.checkpoint.max-materialize-attempts`
-- attempts without producing a snapshot is no longer retried by the nightly pass and no longer
-- names its site. It is not a dead end — an ordinary incremental build (new segments) still writes
-- every table in its fold, a forced rebuild re-arms the row deliberately, and any success resets
-- the counter to zero. `delta.checkpoint.tables.given-up` gauges the population so that giving up
-- is visible rather than silent.
--
-- Both columns are backward compatible: existing rows start at zero attempts, i.e. fully
-- retryable, which is exactly their behaviour before this migration.

ALTER TABLE checkpoints
    ADD COLUMN materialize_attempts       INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_materialize_failure_at TIMESTAMP;

COMMENT ON COLUMN checkpoints.materialize_attempts IS
    'Consecutive failed attempts to materialize this table''s Parquet snapshot; reset to 0 by any '
    'success and by a forced rebuild. At delta.checkpoint.max-materialize-attempts the nightly '
    'rematerialize stops retrying the row (issue #149).';
COMMENT ON COLUMN checkpoints.last_materialize_failure_at IS
    'When the attempt counted in materialize_attempts last failed; NULL while the row has a '
    'snapshot.';

-- The work-list query is "unmaterialized AND still retryable", so both predicates belong in the
-- index. Partial on the null key because that is the small side: the overwhelming majority of
-- checkpoint rows carry a snapshot and must never be scanned for this.
CREATE INDEX idx_checkpoints_retryable ON checkpoints (materialize_attempts, site_id)
    WHERE s3_key_parquet IS NULL;

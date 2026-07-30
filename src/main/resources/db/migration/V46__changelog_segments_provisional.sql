-- V46: provisional changelog segments — segmented re-baseline (033, issue #82).
--
-- A FULL_SNAPSHOT larger than delta.ingestion.max-session-records used to be impossible: a
-- re-baseline never sealed segments, so the whole snapshot buffered on-heap and overflowed, and the
-- overflow told the client to re-baseline again. It now seals on the CONTINUOUS thresholds, which
-- means partial snapshot segments exist on disk while the session is still streaming.
--
-- provisional = TRUE marks exactly those: durable, but invisible to the checkpoint fold, the
-- delta-Parquet egress queue and the Bit BI SQL queue. SessionEnd flips the whole batch to FALSE in
-- the same transaction that discards the previous baseline, so the swap is atomic and a drop
-- mid-snapshot leaves the old baseline serving unchanged.

-- Every existing segment is a committed baseline: FALSE is the correct value and the default keeps
-- the ALTER cheap (PG 11+ stores it in the catalog, no table rewrite).
ALTER TABLE changelog_segments
    ADD COLUMN provisional BOOLEAN NOT NULL DEFAULT FALSE;

-- Both work queues pick the per-site head with FOR UPDATE SKIP LOCKED. Excluding provisional rows in
-- the index (not just the WHERE clause) keeps the head lookup on the partial index and stops an
-- in-flight snapshot from blocking the queue: without this, the lowest-first_seq pending row would
-- be a provisional segment that never becomes claimable until the session ends.
DROP INDEX IF EXISTS idx_changelog_segments_egress_pending;
CREATE INDEX idx_changelog_segments_egress_pending
    ON changelog_segments (site_id, first_seq)
    WHERE egress_at IS NULL AND provisional = FALSE;

DROP INDEX IF EXISTS idx_changelog_segments_plugin_sql_pending;
CREATE INDEX idx_changelog_segments_plugin_sql_pending
    ON changelog_segments (site_id, first_seq)
    WHERE plugin_sql_at IS NULL AND provisional = FALSE;

-- Start-of-snapshot garbage collection: a re-baseline that never completed leaves provisional rows
-- behind, and the next attempt deletes them by site before streaming.
CREATE INDEX idx_changelog_segments_provisional
    ON changelog_segments (site_id)
    WHERE provisional = TRUE;

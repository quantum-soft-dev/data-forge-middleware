-- V46: provisional changelog segments — segmented re-baseline (033, issue #82).
--
-- A FULL_SNAPSHOT larger than delta.ingestion.max-session-records used to be impossible: a
-- re-baseline never sealed segments, so the whole snapshot buffered on-heap and overflowed, and the
-- overflow told the client to re-baseline again. It now seals on bounded thresholds, which means
-- partial snapshot segments exist on disk while the session is still streaming.
--
-- provisional = TRUE marks exactly those: durable, but invisible to the checkpoint fold, the
-- delta-Parquet egress queue and the Bit BI SQL queue. SessionEnd flips the whole batch to FALSE in
-- the same transaction that discards the previous baseline, so the swap is atomic and a drop
-- mid-snapshot leaves the old baseline serving unchanged.

-- Every existing segment is a committed baseline: FALSE is the correct value and the default keeps
-- the ALTER cheap (PG 11+ stores it in the catalog, no table rewrite).
ALTER TABLE changelog_segments
    ADD COLUMN provisional BOOLEAN NOT NULL DEFAULT FALSE;

-- Split the uniqueness keyspace (review finding, #82).
--
-- uk_segment_site_first_seq (V30) was a table constraint over ALL rows. That made a provisional
-- segment compete for (site_id, first_seq) slots with the very baseline it is replacing — which is
-- only deleted at SessionEnd — so a snapshot re-bootstrapping at an already-used first_seq died on
-- its first seal and could never complete. The same slot collision let an abandoned snapshot's
-- orphan block every subsequent ordinary session at that sequence, wedging the site.
--
-- A provisional row is invisible to every consumer; it must be invisible to the uniqueness rule
-- too. Uniqueness is therefore enforced only over published rows, which is where it actually
-- protects the served baseline. Provisional rows are not unique-indexed on purpose: a retry that
-- reuses the same sequence range must not deadlock against leftovers it is about to collect, and
-- intra-session duplicates cannot arise (each seal advances firstSeq to committedSeq + 1). Should a
-- duplicate ever reach publication, the flip violates the committed index and the whole commit
-- rolls back — a safe failure, not a corrupted baseline.
ALTER TABLE changelog_segments DROP CONSTRAINT uk_segment_site_first_seq;

CREATE UNIQUE INDEX uk_segment_site_first_seq
    ON changelog_segments (site_id, first_seq)
    WHERE provisional = FALSE;

-- Garbage collection of provisional segments left by a snapshot that never reached SessionEnd:
-- swept by batch (the batch a session owns) and, for crash leftovers, by scanning provisional rows
-- whose batch is no longer running.
CREATE INDEX idx_changelog_segments_provisional
    ON changelog_segments (batch_id)
    WHERE provisional = TRUE;

-- NOTE: the egress / plugin-sql pending indexes are deliberately NOT changed. A provisional segment
-- parks its egress_at / plugin_sql_at at a sentinel timestamp, so it is already outside both partial
-- indexes and outside the "IS NULL = pending" predicate every poller uses. That keeps a replica
-- running the PREVIOUS release — which has no notion of `provisional` — from claiming a
-- half-streamed snapshot during a rolling deploy and stamping it processed for good.

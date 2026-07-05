-- V33: Delta Client v2 — event-driven egress queue marker (022, Task 8).
-- egress_at IS NULL = the segment awaits delta-Parquet materialization; the table itself is the
-- durable work queue (picked FOR UPDATE SKIP LOCKED, per-site head first, so files publish in
-- seq order per site while sites drain in parallel).

ALTER TABLE changelog_segments ADD COLUMN egress_at TIMESTAMP;

-- Pre-V33 segments were served by the (retired) floor/change-feed egress — backfill them as
-- egressed so the sweep does not materialize historical deltas on deploy; a consumer starting
-- fresh triggers a FULL_SNAPSHOT session instead.
UPDATE changelog_segments SET egress_at = created_at;

-- Pending-head lookup: per-site earliest pending segment.
CREATE INDEX idx_changelog_segments_egress_pending
    ON changelog_segments (site_id, first_seq)
    WHERE egress_at IS NULL;

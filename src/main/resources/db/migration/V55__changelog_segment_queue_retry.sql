-- V55: per-segment retry state for the two changelog work queues (issue #243).
--
-- Both queues claim the globally oldest per-site head with LIMIT 1, so one segment whose work
-- deterministically throws was offered first on every wake and no other site's SQL or delta
-- Parquet was ever produced. These columns let a failed attempt defer that one segment with an
-- exponential backoff: its own site keeps waiting (per-site seq order is a contract) while the
-- next wake claims a different site's head, where before the same segment was offered first for
-- ever.
--
-- Nothing here discards work: there is no attempt ceiling that gives up. The counters escalate
-- reporting (delta.egress.segments.poisoned / sql.generation.delta.segments.poisoned) and the
-- outer horizon stays batch retention (#212).

ALTER TABLE changelog_segments
    ADD COLUMN plugin_sql_attempts INTEGER   NOT NULL DEFAULT 0,
    ADD COLUMN plugin_sql_retry_at TIMESTAMP,
    ADD COLUMN egress_attempts     INTEGER   NOT NULL DEFAULT 0,
    ADD COLUMN egress_retry_at     TIMESTAMP;

COMMENT ON COLUMN changelog_segments.plugin_sql_attempts IS
    'Consecutive failed Bit BI delta-SQL generation attempts for this segment (issue #243). Reset '
    'to 0 when a plugin reinit re-enqueues the site.';
COMMENT ON COLUMN changelog_segments.plugin_sql_retry_at IS
    'Not claimable by the delta-SQL queue before this instant (UTC); NULL = claimable now.';
COMMENT ON COLUMN changelog_segments.egress_attempts IS
    'Consecutive failed delta-Parquet egress attempts for this segment (issue #243).';
COMMENT ON COLUMN changelog_segments.egress_retry_at IS
    'Not claimable by the egress queue before this instant (UTC); NULL = claimable now.';

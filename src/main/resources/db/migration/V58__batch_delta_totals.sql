-- V58: a batch keeps its Delta v2 session totals (issue #346)
--
-- Upload History and Batch Detail computed a session's totals — records, tables, per-table
-- counts, seq range — from changelog_segments every time they were read. A segment is a working
-- unit of the delta queues and of retention, and ChangelogRetentionService.prune deletes it once
-- a checkpoint covers it, so the morning after its first checkpoint a 5-million-record snapshot
-- read as the 20 segments of the audit window, and with audit-window-segments = 0 as nothing.
--
-- The totals are added to the batch in the transaction that commits each segment (a seal, the
-- SessionEnd tail, the provisional segments a re-baseline publishes), because a CONTINUOUS
-- session can outlive a nightly checkpoint and lose its early segments while still running.
--
-- total_records IS NULL means "not tracked": the row predates V58, or was started by a pre-V58
-- pod during the rolling deploy, so some of its segments were never counted. History keeps
-- reading such a batch from its segments, exactly as before. New batches start at zero.
--
-- The columns stay nullable with no default, on purpose: a default of 0 would mark every row
-- an old pod inserts during the rollout as tracked while nothing ever adds to it.

ALTER TABLE batches
    ADD COLUMN total_records BIGINT,
    ADD COLUMN table_count   INTEGER,
    ADD COLUMN table_stats   JSONB,
    ADD COLUMN first_seq     BIGINT,
    ADD COLUMN last_seq      BIGINT;

COMMENT ON COLUMN batches.total_records IS
    'Records in the Delta v2 session''s committed changelog segments, added as each commits '
    '(issue #346). NULL when the batch does not track its totals (started before V58).';
COMMENT ON COLUMN batches.table_count IS
    'Distinct tables in table_stats (issue #346).';
COMMENT ON COLUMN batches.table_stats IS
    'Per-table insert/update/delete counts across the session, the shape of '
    'changelog_segments.stats: {"<table>":{"inserts":n,"updates":n,"deletes":n}} (issue #346).';
COMMENT ON COLUMN batches.first_seq IS
    'Lowest sequence number of the session''s committed segments; NULL until one is recorded.';
COMMENT ON COLUMN batches.last_seq IS
    'Highest sequence number of the session''s committed segments; NULL until one is recorded.';

-- Backfill the finished batches that still have published segments, so what they show today
-- stops shrinking. Only finished ones: an IN_PROGRESS batch may still be written by a pre-V58
-- pod that does not add to these columns, and stays on the segment read. A finished batch whose
-- segments were already partly pruned keeps what remained — the loss happened before this ran.
-- Provisional segments are excluded: a finished batch still holding one discarded it (033).
WITH segment_totals AS (
    SELECT s.batch_id,
           SUM(s.record_count) AS total_records,
           MIN(s.first_seq)    AS first_seq,
           MAX(s.last_seq)     AS last_seq
    FROM changelog_segments s
    JOIN batches b ON b.id = s.batch_id
    WHERE s.provisional = FALSE
      AND b.status <> 'IN_PROGRESS'
    GROUP BY s.batch_id
),
table_totals AS (
    SELECT s.batch_id,
           t.key                                   AS table_name,
           SUM((t.value ->> 'inserts')::BIGINT)    AS inserts,
           SUM((t.value ->> 'updates')::BIGINT)    AS updates,
           SUM((t.value ->> 'deletes')::BIGINT)    AS deletes
    FROM changelog_segments s
    JOIN batches b ON b.id = s.batch_id
    CROSS JOIN LATERAL jsonb_each(COALESCE(s.stats, '{}'::JSONB)) AS t
    WHERE s.provisional = FALSE
      AND b.status <> 'IN_PROGRESS'
    GROUP BY s.batch_id, t.key
),
table_json AS (
    SELECT batch_id,
           jsonb_object_agg(table_name, jsonb_build_object(
                   'inserts', inserts, 'updates', updates, 'deletes', deletes)) AS table_stats,
           COUNT(*)                                                            AS table_count
    FROM table_totals
    GROUP BY batch_id
)
UPDATE batches b
SET total_records = st.total_records,
    first_seq     = st.first_seq,
    last_seq      = st.last_seq,
    table_stats   = COALESCE(tj.table_stats, '{}'::JSONB),
    table_count   = COALESCE(tj.table_count, 0)
FROM segment_totals st
LEFT JOIN table_json tj ON tj.batch_id = st.batch_id
WHERE b.id = st.batch_id;

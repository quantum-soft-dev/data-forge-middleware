-- V60: a snapshot batch whose segments were pruned before V58 gets its totals from its
-- completed-batch Parquet artifacts (issue #349)
--
-- V58 backfilled a finished batch's totals from the changelog segments still present when it ran.
-- ChangelogRetentionService.prune had already deleted the oldest segments of some batches, so V58
-- recorded what remained — on dev a 5 012 611-record, 87-table FULL_SNAPSHOT read as 363 259
-- records in 13 tables — and a batch with no segment left stayed untracked and showed nothing.
-- The records were never lost, only their count: every such batch keeps one batch_parquet_artifacts
-- row per table (036), with the exact row_count and, since V51, its seq range, and retention does
-- not touch those rows.
--
-- Scope, decided on the ticket:
--   * FULL_SNAPSHOT only. Such a session is all INSERTs by contract, so an artifact's row_count is
--     exactly that table's inserts. A DELTA or CONTINUOUS artifact counts records of every
--     operation, which SQL cannot split into inserts/updates/deletes, and table_stats has no slot for
--     "unknown"; those batches keep what V58 recorded. A batch with no session_mode (started before
--     V47) is not proven a snapshot and is skipped for the same reason.
--   * Merged table by table. A table with a READY artifact takes its row_count; a table whose
--     artifact never became READY (ABANDONED, or still queued) keeps what its remaining segments
--     recorded, and with none it stays absent. The seq range spans both.
--   * Never lower. A batch is rewritten only when the merge describes at least as many records and
--     tables as it holds, and only when something differs — a batch whose segments were never
--     pruned already matches its artifacts and is not written at all.
--   * A running batch is left to its segments, as V58 left it (BatchHistoryService reads an
--     IN_PROGRESS batch from its segments whatever it stores).

WITH candidates AS (
    SELECT b.id
    FROM batches b
    WHERE b.session_mode = 'FULL_SNAPSHOT'
      AND b.status <> 'IN_PROGRESS'
      AND EXISTS (SELECT 1
                  FROM batch_parquet_artifacts a
                  WHERE a.batch_id = b.id
                    AND a.status = 'READY')
),
ready_tables AS (
    SELECT a.batch_id,
           a.table_name,
           a.row_count AS inserts,
           0::BIGINT   AS updates,
           0::BIGINT   AS deletes
    FROM batch_parquet_artifacts a
    JOIN candidates c ON c.id = a.batch_id
    WHERE a.status = 'READY'
),
segment_tables AS (
    SELECT s.batch_id,
           t.key                                AS table_name,
           SUM((t.value ->> 'inserts')::BIGINT) AS inserts,
           SUM((t.value ->> 'updates')::BIGINT) AS updates,
           SUM((t.value ->> 'deletes')::BIGINT) AS deletes
    FROM changelog_segments s
    JOIN candidates c ON c.id = s.batch_id
    CROSS JOIN LATERAL jsonb_each(COALESCE(s.stats, '{}'::JSONB)) AS t
    WHERE s.provisional = FALSE
      AND NOT EXISTS (SELECT 1
                      FROM ready_tables r
                      WHERE r.batch_id = s.batch_id
                        AND r.table_name = t.key)
    GROUP BY s.batch_id, t.key
),
merged_tables AS (
    SELECT batch_id, table_name, inserts, updates, deletes FROM ready_tables
    UNION ALL
    SELECT batch_id, table_name, inserts, updates, deletes FROM segment_tables
),
merged AS (
    SELECT batch_id,
           SUM(inserts + updates + deletes) AS total_records,
           COUNT(*)                         AS table_count,
           jsonb_object_agg(table_name, jsonb_build_object(
                   'inserts', inserts, 'updates', updates, 'deletes', deletes)) AS table_stats
    FROM merged_tables
    GROUP BY batch_id
),
seq_bounds AS (
    SELECT batch_id,
           MIN(first_seq) AS first_seq,
           MAX(last_seq)  AS last_seq
    FROM (SELECT a.batch_id, a.first_seq, a.last_seq
          FROM batch_parquet_artifacts a
          JOIN candidates c ON c.id = a.batch_id
          WHERE a.status = 'READY'
          UNION ALL
          SELECT s.batch_id, s.first_seq, s.last_seq
          FROM changelog_segments s
          JOIN candidates c ON c.id = s.batch_id
          WHERE s.provisional = FALSE) AS bounds
    GROUP BY batch_id
)
UPDATE batches b
SET total_records = m.total_records,
    table_count   = m.table_count,
    table_stats   = m.table_stats,
    first_seq     = q.first_seq,
    last_seq      = q.last_seq
FROM merged m
JOIN seq_bounds q ON q.batch_id = m.batch_id
WHERE b.id = m.batch_id
  AND m.total_records >= COALESCE(b.total_records, 0)
  AND m.table_count >= COALESCE(b.table_count, 0)
  AND (b.total_records, b.table_count, b.table_stats, b.first_seq, b.last_seq)
      IS DISTINCT FROM (m.total_records, m.table_count, m.table_stats, q.first_seq, q.last_seq);

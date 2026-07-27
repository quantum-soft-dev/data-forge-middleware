# Data Model: Batch-per-Session (029)

## Changed entities

### Batch (`batches`) — modified

| Field | Type | Change | Notes |
|---|---|---|---|
| `last_activity_at` | `TIMESTAMP` NULL | **new (V40)** | Touched by V2 ingestion at session start/resume, at each Ack watermark (≥100 accepted records), and at each segment seal. Always NULL for v1 file-upload batches. |

Semantics change (no schema impact): one batch per ingestion session. Lifecycle:

```
SessionStart ──► IN_PROGRESS
   seal (×N) ──── (no transition; segments accumulate)
SessionEnd  ──► COMPLETED          (aggregates rendered from segments)
abort/error ──► FAILED
silent > timeout (COALESCE(last_activity_at, started_at) < cutoff) ──► NOT_COMPLETED
transport drop ── stays IN_PROGRESS (staged resume re-attaches)
```

### ChangelogSegment (`changelog_segments`) — relationship change only

- `batch_id`: now genuinely **many-to-one** to `batches` (schema always allowed it; code stops assuming 1:1).
- `s3_key`: new rows written as `delta/{siteId}/segments/{segmentId}.pb.gz`; existing rows keep their stored `{batchId}`-based keys (column is authoritative, no backfill).
- New index **`idx_changelog_segments_batch_id (batch_id)`** (V40) — history detail and list aggregation query by batch id.

## Migration V40

```sql
ALTER TABLE batches ADD COLUMN last_activity_at TIMESTAMP;
COMMENT ON COLUMN batches.last_activity_at IS
  'Last ingestion activity of a Delta v2 streaming session (NULL for v1 batches); batch timeout uses COALESCE(last_activity_at, started_at)';
```

Backward compatible: nullable column; forward-only. The `changelog_segments.batch_id` index
already exists (V37 `idx_segment_batch_id`). V39 is reserved by the in-flight 028 branch — if 029
merges first, 028 renumbers.

## Query changes

### Expired batches (sweeper)

```sql
-- before: started_at < :cutoff AND status = 'IN_PROGRESS'
-- after:
WHERE status = 'IN_PROGRESS'
  AND COALESCE(last_activity_at, started_at) < :cutoff
```

v1 batches (`last_activity_at IS NULL`) — behavior identical to today.

### List-view delta aggregation (new repository query)

Per page of batch ids, one grouped query:

```sql
SELECT s.batch_id                          AS batch_id,
       SUM(s.record_count)                 AS total_records,
       COUNT(DISTINCT t.table_name)        AS table_count
FROM changelog_segments s
LEFT JOIN LATERAL jsonb_object_keys(COALESCE(s.stats, '{}'::jsonb)) AS t(table_name) ON TRUE
WHERE s.batch_id IN (:batchIds)
GROUP BY s.batch_id
```

Replaces the current "pick one segment per batch" bulk fetch feeding `BatchSummaryDto`.
(Detail view already aggregates the full segment list in memory — unchanged.)

## Invariants

- One active (IN_PROGRESS) batch per site — unchanged; a live session holds it for its whole duration.
- `uk_segment_site_first_seq (site_id, first_seq)` — unchanged; still guarantees non-overlapping segment ranges per site.
- Segments of one batch have contiguous, ascending seq ranges (session-scoped watermark) — unchanged.
- `BatchCompletedEvent` fires exactly once per batch → now exactly once per session.

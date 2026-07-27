# Research: Batch-per-Session (029)

## Current behavior (verified in code, develop @ 2026-07-27)

### Where the per-100 batch churn comes from

`DeltaIngestionService` (gRPC `StreamChanges`):
- `CONTINUOUS_SEAL_RECORDS = 100` (hardcoded), plus time (`continuous-seal-millis`) and byte (`continuous-seal-bytes`) triggers.
- `sealContinuous(boolean continueStream)`:
  1. `emitSealed(...)` → `DeltaSessionCommitService.commit(...)` which persists the segment **and calls `batchLifecycleService.completeBatch(batchId)`** (+ afterCommit dispatch).
  2. If `continueStream`: immediately `batchLifecycleService.startBatch(...)` for the next segment.
- Clean close (`SessionEnd` in continuous mode) → `sealContinuous(false)` seals the (possibly empty) tail buffer → the observed `0 files / 0 B` row.

Observed in production (test env, site `fyt-new`, 2026-07-27 07:41 UTC): a single client session streaming 200 records of one table produced 3 batches (100, 100, 0).

### Segment storage & model

- `ChangelogSegment` already has its own `id` (UUID) plus `batch_id`, `first_seq`, `last_seq`, `record_count`, `stats` (jsonb per-table counts), `mode`, `s3_key`, `egress_at` (egress work-queue marker).
- **Decision-relevant**: the entity already supports N segments : 1 batch; nothing in the DB schema enforces 1:1. `uk_segment_site_first_seq (site_id, first_seq)` stays valid.
- S3 key is currently derived from the batch: `delta/{siteId}/segments/{batchId}.pb.gz` (`S3ChangelogSegmentStorage.uploadSegment(siteId, batchId, content)`). With N segments per batch this collides → must key by segment id.

### Batch history read paths

- Detail view (`BatchHistoryService.getBatchDetails`): already fetches `findByBatchId(batchId)` as a **list** and aggregates (`resolveDeltaStats` merges per-table stats across segments, `resolveSeqRange` takes min/max, `resolveMode` takes first). Multi-segment batches render correctly with no change.
- List view (cursor pagination): bulk-fetches one `ChangelogSegment` per batch id (`Map<UUID, ChangelogSegment>`, merge-pick-one on duplicates) and surfaces `segment.recordCount` / `stats.size()` in `BatchSummaryDto.fromProjection`. With N segments this **undercounts** → needs per-batch aggregation (sum of record_count, distinct table count).

### Batch timeout

- `BatchTimeoutScheduler`: `cutoff = now - timeoutMinutes(60)`, `batchRepository.findExpiredBatches(cutoff)` — keyed off `started_at` only. A single long-lived session batch would be killed mid-stream → needs activity-based cutoff for V2 streaming batches.
- `Batch` entity has `started_at`, `completed_at`, `created_at`, `version` — **no activity timestamp** → new nullable column.

### Events / plugins / egress

- `DeltaSessionCommitService.commit` → `completeBatch` → `BatchCompletedEvent` → `BatchEventListener` (AFTER_COMMIT) → `PluginEventDispatcher` (`BATCH_COMPLETED` per completed batch). Since today each segment completes a batch, plugins get one event per segment. Once seal stops completing batches, exactly-once-per-session falls out automatically — no plugin code changes.
- Delta Parquet egress is queued off the **segment** (`egress_at` marker, `DeltaEgressWorker`), not off batch completion → untouched by this feature (verify in integration test).
- Bit BI delta SQL (026) consumes segments via its own queue/baselines — per-segment, untouched.

### Staged resume

- `stageForResume()` retains `batchId` + buffer; resume re-attaches to the same batch. Already compatible with batch-per-session (the batch simply spans the drop). No change.

## Decisions

| # | Decision | Rationale | Alternatives rejected |
|---|----------|-----------|----------------------|
| D1 | Seal commits a segment under the session's open batch; batch lifecycle moves entirely to session boundaries (start/end/abort). | Product decision: batch = one upload attempt. | Configurable seal threshold (keeps churn, just less often); UI-side row collapsing (lies about the data model). |
| D2 | Split `DeltaSessionCommitService.commit` into `commitSegment(...)` (persist segment + egress queueing, no batch completion) and keep batch completion only on the SessionEnd path. | Minimal change preserving transactional/AFTER_COMMIT semantics per segment. | Flag parameter on existing `commit` (two responsibilities in one method, error-prone). |
| D3 | Segment S3 key: `delta/{siteId}/segments/{segmentId}.pb.gz` (segment's own UUID). | Prevents collision of N segments in one batch; `s3_key` is stored per row so old keys stay readable — no data migration. | Key by `{batchId}/{firstSeq}` (works but couples storage layout to two identities for no benefit). |
| D4 | New nullable `batches.last_activity_at` (V41); touched by ingestion at bounded cadence (session start/resume, each Ack watermark i.e. every ≥100 accepted records, each seal). Sweeper cutoff: `COALESCE(last_activity_at, started_at) < cutoff`. | Live long session survives; silent one is reclaimed; v1 batches (column always NULL) keep exact current behavior. | Separate heartbeat table (overkill); bumping `version`/optimistic-lock field (semantic abuse); gRPC keepalive as liveness signal (doesn't prove data flow). |
| D5 | List-view aggregation via a grouped repository query returning per-batch `(SUM(record_count), COUNT(DISTINCT table))` for the page's batch ids (native query with `jsonb_object_keys` lateral for the table union). | Correct totals without loading every segment row of a million-record run into memory. | In-memory aggregation of all segments per page (unbounded rows for big runs); denormalized counters on Batch (write-path complexity, drift risk). |
| D6 | Empty session (0 accepted records) completes its single batch with 0 changes; the tail-batch pre-open disappears (no `startBatch` inside seal). | Spec FR-004/FR-005. | Suppressing empty batches entirely (hides real failed/empty attempts). |
| D7 | No forced rollover for perpetual continuous sessions. | Product decision (Clarifications). | Daily batch rollover (reintroduces artificial batch boundaries). |

## Risks / notes

- `completeBatch` on a batch with zero uploaded files previously never happened for v1; V2 already completes 0-file batches (whole session data lives in segments), so no status-model change needed.
- The Ack-cadence touch writes one UPDATE per ~100 records on the hot ingest path — same order of magnitude as today's per-100 `completeBatch`+`startBatch` pair, so DB load strictly decreases.
- `findExpiredBatches` gains COALESCE — verify the sweeper query still uses an index (status filter is the selective predicate; current behavior preserved for v1).
- Frontend: no changes — `BatchSummaryDto` field names/meanings unchanged (`deltaRecordCount` now the true total).
- gRPC proto: unchanged. Client-visible protocol behavior unchanged except fewer batch boundaries server-side (invisible to the client).

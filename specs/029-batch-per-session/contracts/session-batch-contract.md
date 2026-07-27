# Contract: Ingestion Session ↔ Batch (029)

No REST or gRPC schema changes. This contract pins the observable behavior.

## gRPC `StreamChanges` (proto unchanged)

| Client action | Server obligation (after 029) |
|---|---|
| `SessionStart` (any mode) | Open exactly one batch for the session (or re-attach to the staged batch on DELTA resume). Site's one-active-batch invariant enforced as today (`ACTIVE_SESSION_EXISTS`). |
| stream records (CONTINUOUS) | Seal a segment every 100 records / seal-time / seal-bytes. Each seal: segment persisted durably under `delta/{siteId}/segments/{segmentId}.pb.gz`, associated with the **same** batch; per-segment egress queued; **no batch completion, no new batch**. `Sealed` event emitted to the client as today. |
| `SessionEnd` (clean) | Final seal of any buffered records, then complete the session's single batch exactly once. Zero-record session → batch completes with 0 changes. No trailing empty batch is ever created. |
| server-side rejection (gap / overflow / schema) or commit failure | Fail the session's single batch. Previously-sealed segments stay durable. |
| transport drop without `SessionEnd` | Batch stays IN_PROGRESS; staged resume re-attaches to it (unchanged). |
| silence > `batch.timeout-minutes` (no accepted data) | Sweeper fails the batch (NOT_COMPLETED), freeing the site — measured from last activity, not batch start. |

## REST `GET /api/v1/history/batches` (DTO shape unchanged)

- `deltaRecordCount`: now SUM of the batch's segment record counts (was: one segment's count).
- `deltaTableCount`: now COUNT of DISTINCT tables across the batch's segments (was: one segment's stats size).
- v1 batches: both remain `null`.
- Batch detail endpoint: already aggregated across segments — unchanged.

## Plugin events

- `BATCH_COMPLETED`: dispatched exactly once per session, after the batch completes (was: once per ~100-record segment). Payload shape unchanged.
- Per-segment pipelines (delta Parquet egress, Bit BI delta SQL queue): unchanged — triggered at segment seal, independent of session end.

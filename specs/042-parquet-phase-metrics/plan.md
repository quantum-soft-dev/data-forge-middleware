# 042 — Parquet / checkpoint duration phases plan

Issue #111 adds phase-tagged timers so a Parquet cycle can be attributed, plus an
egress queue-depth gauge.

## Design

1. `DeltaMetrics` pre-registers one `Timer` per allowlisted phase on the existing
   meter names (`delta.batch-parquet.duration`, `delta.checkpoint.duration`) and
   the new `delta.egress.duration`. The cycle is `{phase=total}` on the same name:
   Prometheus rejects mixed tagged/untagged series.
2. Streaming replay attribution lives next to the I/O, not by buffering segments:
   - `ChangelogSegmentService.forEachRecord` uses a thread-bound `ReplayPhaseClock`
     that counts `GetObject`/stream-read nanos as `download` and parse-minus-consumer
     as `decode`. Callers that mock the service are unaffected.
   - `DeltaParquetWriter.writeBatchDeltaParquet` reports only the decimal-scan
     consumer and the write/close work through an optional `PhaseListener`.
   - `BatchParquetFinalizationService` binds the clock around the shared replay,
     records writer phases, and times each `uploadBatchParquet`.
3. `DeltaEgressService.egressSegment` times `readRecords` as `download`,
   `toDeltaParquet` as `write`, and `uploadDelta` as `upload`, plus an untagged
   cycle around the same work.
4. `CheckpointService.buildCheckpoint` moves frame download inside the existing
   untagged `timeCheckpoint` so the cycle includes it, then records
   `download_frame` / `fold` / `parquet` / `upload`. CSV encode and DB writes stay
   in the untagged total only — they are not named phases.
5. `EgressPendingMetrics` (`MeterBinder`) exports `delta.egress.pending` from
   `ChangelogSegmentRepository.countPendingEgress()`, with the same 5 s shared
   snapshot TTL as `BatchParquetQueueMetrics`.

## Test strategy

- Unit: `DeltaMetrics` registers every phase, records only allowlisted tags,
  preserves untagged cycle timers, ignores negative nanos.
- Unit: `EgressPendingMetrics` reads the pending count and refreshes after TTL.
- Unit: `ChangelogSegmentService` attributes download vs decode on a real stream;
  `DeltaParquetWriter` reports `decimal_scan` and `write`.
- Unit: finalization / egress / checkpoint workers record the phases named in the
  spec on a successful build (and still settle the same outcomes on failure).
- Contract: `/actuator/prometheus` includes `delta_egress_pending` and at least
  one `phase=` label on each duration meter.
- Gates: `./gradlew test -PexcludeIntegration` before each code commit;
  `./gradlew integrationTest` before the PR.

## Documentation surfaces

- Needed: `docs/delta-client-v2-guide.md` (exported names + reading rule),
  `docs/cr-unified-batch-parquet.md` (batch-parquet phases), `CLAUDE.md` Recent
  Changes, this spec.
- Not needed: protobuf/wire-contract docs, client plugin guides, frontend/UI docs,
  CI/deployment docs, root `CHANGELOG.md`, a migration pointer change.

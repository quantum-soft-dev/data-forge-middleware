# 027 — Delta v2 session byte budget (byte-aware OOM guard)

## Context

The Delta v2 gRPC ingest OOM-guard was row-count-based only
(`delta.ingestion.max-session-records`, default 2,000,000): a non-CONTINUOUS session buffers all
records on-heap until commit, so a fat-but-under-2M-rows dataset could exhaust the heap. In practice
a 439k-row full snapshot OOMed a pod with a 1536Mi limit (~1.15Gi heap) on 2026-07-23; the infra
workaround was bumping dev memory to 3Gi (PR #51). This feature adds a byte-aware guard so such a
session degrades into a clean SESSION-level error (or bounded segments in CONTINUOUS mode) instead
of killing the JVM.

## Design

- `SessionChangeBuffer` tracks the cumulative **serialized** size of accepted records
  (`ChangeRecord.getSerializedSize()`, memoized by protobuf) and answers `OVERFLOW_BYTES` when
  accepting a record would exceed a byte budget. Serialized size approximates the buffer's heap
  weight; retained heap is roughly 3–5x serialized.
- `delta.ingestion.max-session-bytes` (`DELTA_MAX_SESSION_BYTES`) bounds a non-CONTINUOUS session.
  `0` (default) = **auto: maxHeap/8**, so the guard scales with the pod — a fixed default would
  either still have OOMed the old 1536Mi pod or would reject the 439k-row snapshot that legitimately
  succeeds on the 3Gi pod. On overflow the session gets `INTERNAL` + `NEED_REBASELINE` (message
  names the byte budget and points at CONTINUOUS mode) and its batch is failed — same contract as
  the record cap.
- `delta.ingestion.continuous-seal-bytes` (`DELTA_CONTINUOUS_SEAL_BYTES`, default 16 MiB) adds a
  **byte seal trigger** to CONTINUOUS mode alongside the 100-record and time triggers, so 100 fat
  records (each may approach the 4MB gRPC message cap) seal into more, smaller segments. It keeps
  the continuous buffer far below the session budget, which then only backstops misconfiguration.
- New meter `delta.sessions.overflow` counts overflow rejections (records or bytes) — the
  observability the OOM incident lacked.
- No proto change: the existing `INTERNAL` error code is reused (as for the record cap); clients
  distinguish by message.

## Tasks

- [x] **T1** — `SessionChangeBuffer`: byte tracking (`acceptedBytes()`) + `Result.OVERFLOW_BYTES`
  (unit tests: byte total excludes duplicates/gaps/overflows; overflow does not advance the
  watermark; a single over-budget record overflows; DUPLICATE/GAP still win over overflow so resume
  replays are not spuriously killed).
- [x] **T2** — `DeltaIngestionService`: `max-session-bytes` config (auto default `maxHeap/8` via
  `resolveMaxSessionBytes`), reject non-CONTINUOUS sessions on `OVERFLOW_BYTES`, fail the batch,
  `delta.sessions.overflow` meter (contract test with a tiny budget; metrics unit test).
- [x] **T3** — CONTINUOUS byte seal trigger via `continuous-seal-bytes` (contract test: seals every
  ~2 records under a tiny threshold, no errors).
- [x] **T4** — Docs (`docs/delta-client-v2-guide.md`: session size limit, continuous seal triggers,
  troubleshooting row), k8s dev patch comment, this spec.
- [x] **T5** (review p.1) — `delta.sessions.overflow` tagged `reason=records|bytes`, so an incident
  can be told apart from the metric alone.
- [x] **T6** (review p.2–4) — fail-fast at startup when `continuous-seal-bytes >= max-session-bytes`
  (reachable via the auto budget on a heap <= 128MiB); the byte-overflow message points at the
  config knobs instead of advising CONTINUOUS to a session that already is; docs/yml no longer
  scope the budget to non-CONTINUOUS sessions (it applies to all, seals make it a backstop).

## Gates

Per-task: `./gradlew test -PexcludeIntegration` green (enforced by pre-commit hook).
Before PR: `./gradlew integrationTest`.

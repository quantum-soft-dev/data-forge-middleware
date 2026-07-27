# Feature Specification: Batch-per-Session Semantics for Delta Client v2 Ingestion

**Feature Branch**: `029-batch-per-session`
**Created**: 2026-07-27
**Status**: Draft
**Input**: User description: "Batch-per-session semantics for Delta Client v2 ingestion. A batch must represent one client upload attempt (one gRPC session): it opens at SessionStart and stays the same batch until the session ends, regardless of how many records are streamed (one row or a million). Continuous-mode segment sealing (every 100 records / time / byte triggers) remains purely a storage-durability mechanism: each sealed segment commits under the same open batch (many segments per batch), and no new batch is opened per segment. Segment S3 keys become per-segment (segments/{segmentId}.pb.gz) instead of per-batch. SessionEnd completes the batch (aggregated stats: total changes = sum of segment record counts, tables = union of segment stats keys, shown as one Upload History row); abort fails it; transport drop keeps it IN_PROGRESS for staged resume (existing mechanism unchanged). No more pre-opened empty tail batches; a session that ends with zero accepted records completes its batch honestly with 0 changes. BATCH_COMPLETED plugin event fires once per session instead of once per 100-record segment. Batch timeout for V2 streaming batches counts from last session activity instead of from batch start. Per-segment egress/queue processing by plugins is unchanged."

## Clarifications

### Session 2026-07-27 (design discussion in chat, prior to /specify)

- Q: What does a batch represent? → A: One client attempt to upload data in one go — "whether it is one row or a million, it is one batch" (product owner decision). Storage granularity must not leak into the UI.
- Q: How should the 60-minute batch timeout treat a long live streaming session? → A: For V2 streaming batches, count the timeout from last session activity (accepted chunk/seal refreshes it), not from batch start; a silent session still frees the site after the timeout. Legacy v1 behavior unchanged.
- Q: What about clients that never close their continuous session? → A: Accepted: one batch stays open for the session's lifetime; no forced rollover for now. Activity-based timeout reclaims silent streams.
- Q: Should a zero-record session produce a history row? → A: Yes — one completed batch with 0 changes (it was a real attempt). The current artificial "0 files / 0 B" pre-opened tail batches must disappear.
- Q: Does per-segment downstream processing wait for session end? → A: No — segment sealing cadence and per-segment egress/queue processing stay exactly as today.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - One upload attempt = one Upload History row (Priority: P1)

An account owner runs their extraction client (e.g. the DBF Data Extractor), which streams a run's worth of changes over a single Delta v2 session in CONTINUOUS mode. Today, every 100 streamed records the server closes the current batch and opens a new one, so a single client run shows up as a string of "100 changes · 1 tables" rows plus a trailing "0 files · 0 B" row. After this feature, the owner opens Upload History and sees exactly one row for that run, with the total change count and the full set of affected tables — no matter whether the run carried one record or a million.

**Why this priority**: This is the core semantic fix the feature exists for. The current behavior misrepresents client activity, floods the history with artificial rows, and confused a real customer during onboarding.

**Independent Test**: Stream a CONTINUOUS session with >100 records (forcing multiple internal segment seals) followed by a clean SessionEnd; verify batch history contains exactly one new completed batch whose aggregated change count equals the total records streamed and whose table set is the union across all sealed segments.

**Acceptance Scenarios**:

1. **Given** a V2 site with no active batch, **When** a client opens a session and streams 250 records of two tables in CONTINUOUS mode and cleanly ends the session, **Then** exactly one batch exists for that session, in COMPLETED state, reporting 250 total changes and both tables.
2. **Given** a CONTINUOUS session that has already sealed two internal segments, **When** the client ends the session with an empty in-memory buffer, **Then** no additional empty batch row appears (the previously observed "0 files / 0 B" trailing row no longer occurs).
3. **Given** a client that opens a session and ends it having streamed zero accepted records, **Then** exactly one batch row exists for the attempt, completed with 0 changes.
4. **Given** a session in any non-CONTINUOUS mode (FULL_SNAPSHOT, DELTA), **When** it commits at SessionEnd, **Then** behavior is unchanged: one batch per session, as today.

---

### User Story 2 - Long-running live session is not killed by the batch timeout (Priority: P2)

A client slowly processes a large set of source files, keeping one streaming session open for longer than the fixed batch timeout (60 minutes) while actively sending data. The system must not fail the batch of a session that is demonstrably alive; it must still reclaim the site (fail the batch) when a session goes silent for the timeout duration.

**Why this priority**: Without this, the P1 change would make any active session longer than the timeout impossible — the timeout sweeper would kill the single long-lived batch mid-stream. It is a prerequisite for correctness of P1 under realistic run durations, but it only manifests for runs longer than the timeout.

**Independent Test**: Simulate a V2 streaming batch whose start time is older than the timeout but whose last-activity time is recent; verify the timeout sweeper leaves it alone. Simulate one whose last-activity time exceeds the timeout; verify it is failed and the site is freed.

**Acceptance Scenarios**:

1. **Given** a V2 streaming batch started 90 minutes ago whose session accepted data 5 minutes ago, **When** the timeout sweeper runs, **Then** the batch remains IN_PROGRESS.
2. **Given** a V2 streaming batch with no session activity for longer than the timeout, **When** the sweeper runs, **Then** the batch is failed and the site can start a new batch.
3. **Given** a legacy (v1 file-upload) batch, **When** the sweeper runs, **Then** its timeout behavior is unchanged (counted from batch start).

---

### User Story 3 - Plugins see one completion event per upload attempt (Priority: P3)

Plugins subscribed to batch completion (Bit BI SQL generation, Parquet Export) currently receive a burst of BATCH_COMPLETED events — one per 100-record slice — for what is logically a single client run. After this feature they receive exactly one event per session, carrying the batch that owns all of the session's segments. Per-segment internal processing (delta Parquet egress, per-segment SQL queue) continues to run as segments seal, without waiting for the session to end.

**Why this priority**: Correctness of downstream consumers follows mostly automatically from P1 (events are emitted on batch completion), but the event contract change deserves its own verification because plugin pipelines were built against the one-segment-per-batch assumption.

**Independent Test**: Stream a multi-segment session with an active plugin subscription; verify exactly one BATCH_COMPLETED dispatch occurs and that all sealed segments are discoverable from that batch; verify per-segment egress artifacts were produced before session end.

**Acceptance Scenarios**:

1. **Given** an account with a plugin subscribed to batch completion, **When** a session seals three segments and ends, **Then** exactly one BATCH_COMPLETED event is dispatched, after the batch completes.
2. **Given** a multi-segment session in progress, **When** each segment seals, **Then** its egress processing (delta Parquet materialization) is triggered per segment without waiting for SessionEnd.
3. **Given** a session's batch, **When** a consumer looks up its changelog segments, **Then** all segments sealed during that session are associated with that one batch.

### Edge Cases

- **Transport drop mid-session (staged resume)**: the batch stays IN_PROGRESS and the resumed session re-attaches to the same batch; segments sealed before and after the drop all belong to it. Resume behavior itself is unchanged.
- **Session abort (server-rejected: sequence gap, overflow, schema violation)**: the single batch is failed once; no partial trail of completed 100-record batches remains from before the failure point. Segments already durably committed before the failure remain committed (changelog durability is unchanged).
- **Commit failure on the final seal at SessionEnd**: the batch must be failed (not left IN_PROGRESS), mirroring existing behavior.
- **Zero-record session**: completes its one batch with 0 changes (an honest empty attempt), not suppressed, not duplicated.
- **Concurrent sessions on different sites of one account**: per-account concurrent batch limits now count one batch per live session rather than briefly-open segment batches; no functional change expected but must not regress.
- **Sweeper vs. live session race**: a batch whose activity timestamp is refreshed concurrently with the sweeper run must not be failed.
- **Pre-existing data**: batches and segments created under the old one-segment-per-batch model remain readable in history; aggregation over old batches (single segment) must render identically to today.
- **Truly perpetual continuous clients**: a client that never closes its session holds one batch open indefinitely; this is accepted for now (no forced rollover), and the activity-based timeout still reclaims it if the stream goes silent.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST create exactly one batch per Delta v2 ingestion session, opened when the session starts and retained until the session terminates, independent of the number of records streamed or internal segments sealed.
- **FR-002**: Sealing a segment in CONTINUOUS mode MUST NOT complete the current batch nor open a new one; the sealed segment MUST be associated with the session's single open batch. Multiple segments MAY reference the same batch.
- **FR-003**: Each sealed segment MUST be stored under a per-segment storage key (derived from the segment's own identity, not the batch's), so multiple segments of one batch cannot collide.
- **FR-004**: A clean session end MUST complete the session's batch exactly once. A session that ends with zero accepted records MUST still complete its batch, reporting zero changes.
- **FR-005**: The system MUST NOT pre-open a successor batch after a segment seal; the "empty trailing batch" artifact MUST no longer be produced.
- **FR-006**: A session failure (server-side rejection or commit failure) MUST fail the session's single batch; a transport drop without SessionEnd MUST leave the batch IN_PROGRESS for staged resume, which re-attaches to the same batch (existing resume mechanism preserved).
- **FR-007**: Batch history (Upload History) MUST present one row per session batch with aggregated totals: total changes = sum of the batch's segment record counts; affected tables = union of the batch's segment table sets. Batches predating this feature MUST continue to render correctly.
- **FR-008**: The BATCH_COMPLETED plugin event MUST be dispatched exactly once per session batch, upon batch completion.
- **FR-009**: Per-segment downstream processing (delta Parquet egress, per-segment plugin queues) MUST continue to trigger as each segment seals, without waiting for session end.
- **FR-010**: For V2 streaming batches, the batch timeout MUST be evaluated against the time of last session activity (any accepted data or seal refreshes it) rather than batch start time. A batch whose session has been silent for the timeout duration MUST be failed, freeing the site. Legacy v1 batch timeout behavior MUST remain unchanged.
- **FR-011**: The one-active-batch-per-site invariant MUST be preserved: a live session holds its site's single active batch for its entire duration.

### Key Entities

- **Batch (upload attempt)**: One client upload session. Owns zero or more changelog segments. Lifecycle: IN_PROGRESS from session start; COMPLETED on clean session end; FAILED on abort/timeout. Carries aggregated presentation stats derived from its segments. Gains a notion of "last activity" for timeout purposes.
- **Changelog Segment**: Immutable durably-stored slice of accepted change records with its own identity, sequence range, record count, and per-table stats. Now related many-to-one to a batch (previously effectively one-to-one). Remains the unit of storage durability and per-segment downstream processing.
- **Ingestion Session**: The live client connection. Maps 1:1 to a batch. Seals segments on size/time/byte triggers without affecting batch lifecycle.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A client run streaming N records over one session (for any N ≥ 0, including N > the seal threshold) produces exactly 1 new Upload History row, and that row's change count equals N.
- **SC-002**: Zero "0 files / 0 B" trailing rows are produced by clean session terminations (previously 1 per CONTINUOUS session).
- **SC-003**: For a multi-segment session, plugins receive exactly 1 batch-completion dispatch (previously 1 per ~100 records).
- **SC-004**: A live streaming session older than the batch timeout continues uninterrupted (0 false timeout kills), while a session silent for the timeout duration is reclaimed within one sweeper interval.
- **SC-005**: Segment durability cadence is unchanged: each ~100-record slice is committed to storage at seal time, before session end (verifiable by per-segment egress artifacts appearing during the session).

# Change request: one batch-level Delta Parquet per table

**Issues:** #93, #97, #98, #100, #109
**Features:** 036-unified-batch-parquet, 038-batch-parquet-single-replay,
039-batch-parquet-queue-ops, 040-batch-parquet-attempt-keys,
041-parquet-export-batch-files

## Decision

Delta v2 keeps three deliberately distinct storage layers:

1. **Raw changelog segments** (`delta/{siteId}/segments/*.pb.gz`) are the authoritative durable
   journal and remain bounded by ingestion seals.
2. **Realtime segment egress** (`egress/{siteId}/{table}/delta/seq=*.parquet`) remains available to
   existing sequential consumers. Parquet Export still lists it behind explicit `type=delta`.
3. **Completed-batch downloads**
   (`egress/{siteId}/batches/{batchId}/attempts/{claimToken}/*.parquet`) are unified user-facing
   artifacts: the manifest exposes exactly one winning file for each table in a closed session.

The owner/admin batch download API resolves layer 3 only. It never scans or guesses among
layer-2 objects. Feature 041 (#109) also lists layer 3 on
`GET /api/v1/plugins/parquet-export/files` as `type=batch` (the unversioned default). Segment
and checkpoint files stay available as explicit filters. Abandoned artifacts are returned
without a download URL so the client can alert rather than silently drift.

## Why raw segments are replayed

Parquet files have independent footers and cannot be byte-concatenated. Raw protobuf records are
therefore replayed in segment sequence order. The finalizer opens one writer per claimed table and
fans each record out by table name. With no decimal columns this is exactly one full changelog
replay for the batch. If any claimed table declares decimal columns, one shared scan computes every
decimal precision envelope, preserving the lossless fallback for understated schemas, and one
shared write replay produces every file.

For `T` claimed tables the replay cost is therefore `1` without decimals or `2` with decimals,
instead of up to `2·T`. Table retries remain independent and may replay a smaller subset later.
Both passes stream; no dataset is retained in memory. The heap bound is the number of simultaneously
open table writers multiplied by one Parquet row-group buffer (plus fixed writer metadata), not the
batch row count. Each completed file stays on disk until its own upload and keeps the existing
per-artifact `delta.batch-parquet.max-temp-bytes` guard.

## Durability and retries

The `batch_parquet_artifacts` manifest is the durable queue and public source of truth. A worker
selects the oldest retryable row, takes a transaction-scoped PostgreSQL advisory lock for its
`batch_id`, then row-locks and claims every currently retryable sibling. The advisory lock closes
the `SKIP LOCKED` race where two workers could select different table rows before either published
`BUILDING`. The worker writes each table to a unique local file, uploads to a key containing that
table claim's random token, and sets `READY` only after that table's `PutObject` succeeds while the
same token still owns the row. A crash or failure cannot expose a row as ready; retries rebuild to
new immutable attempt objects. Schema, render, size, row-count, and upload failures remain isolated
per table; failure to replay an authoritative raw segment fails the whole claimed batch because no
output can prove completeness.

The claim is committed **before** the build runs, in its own transaction, and `BUILDING` — not a
held row lock — is what keeps other replicas off the row. That is what makes the attempt ceiling
real: a process that dies mid-build (an OOM on a large batch, a pod eviction) has already made its
incremented `attempt_count` durable, so the most expensive failure class cannot loop forever. It
also keeps the long S3 work off a database connection.

Each table claim mints a `claim_token`, and the batch owner renews every lease (`updated_at`) every third of
`delta.batch-parquet.lease-seconds` (default 30 min) while it builds. The lease therefore bounds
*worker death*, not how long a build may legitimately take — without the renewal it would be a hard
ceiling on build time, and any batch slower than it would be reclaimed mid-flight and rebuilt from
scratch by the next worker, spending an attempt and a pool slot on every lapse. The token settles
the manifest race the lease cannot: a reclaim mints a new one, so a previous owner that surfaces
late finds its token stale and cannot publish metadata over the successor. The token also isolates
the physical upload:
`egress/{siteId}/batches/{batchId}/attempts/{claimToken}/{encodedTable}.parquet`. A superseded
`PutObject` can therefore complete last without touching the successor's bytes. Its publisher
deletes that exact stale key best-effort; if the process dies before publication, lifecycle cleanup
can still find it under the deterministic batch/site prefixes.

Retries are capped at `delta.batch-parquet.max-attempts` (default 7), with the failure backoff
doubling per attempt. Seven attempts span `60+120+240+480+960+1920s ≈ 63 min`, so a transient S3
outage has to outlast an hour before in-flight artifacts are given up on. Several failure classes
are deterministic and never recover — a table with no declared schema, data the declared schema
cannot render, or a batch whose segments a re-baseline removed — and rebuilding them forever would
re-download and re-write the whole batch changelog on a loop. Past the cap the row becomes
`ABANDONED`, logged at ERROR, and is never claimed again, which matches how the per-segment egress
worker already treats an unrenderable table (skip, don't wedge the queue). Once the underlying
cause is fixed, an operator can use the audited admin recovery endpoint below; direct SQL is not
part of the runbook.

The ceiling is applied on **both** paths that can end an attempt. `publish()` cannot run for a build
that never returns, so before each claim the worker bulk-abandons expired `BUILDING` rows whose
attempt budget is spent; the retry query excludes them. Any number of dead claims therefore settles
without hiding live work behind a fixed scan window. Without that, an artifact whose build reliably
kills its process would be re-claimed every lease forever — `attempt_count` growing without bound,
the ERROR never logged, and the download answering `409 "still finalizing"` for the life of the site.

That settlement stamps `updated_at` from the catalog watermark under the catalog publish lock, so an
`ABANDONED` row stays ordered against the `READY` rows a peer is publishing — the `since` guarantee
the Parquet Export catalog rests on. Both are cluster-wide costs — one advisory lock every worker queues on
and one write to a single-row table — while a claim actually dying is rare and the settle step runs
on every drain iteration of every worker on every replica. The predicate is therefore **read first**,
outside any lock, over the existing partial claim index; only a non-empty answer opens the settle
transaction (115). An idle poll costs one index probe. Losing the race to a peer that settles the row
in between costs one wasted watermark tick and never a missed settlement: the row stays claimable
until some transaction actually stamps it.

`delta.batch-parquet.max-temp-bytes` is enforced by the file output stream while Parquet is written,
not after a complete oversized file has consumed the disk budget. Crossing it is deterministic for
the same input and is therefore `ABANDONED` on the first attempt instead of rewritten up to seven
times.

The writer deletes its scratch file in `finally`. A process that dies between `createTempFile` and
that delete leaves the file behind; when `temp-dir` is a persistent volume the next replica does
not inherit an empty directory. `ParquetScratchOrphanSweeper` (issue #127) lists the configured
checkpoint and completed-batch temp directories — they default to the same `java.io.tmpdir` — and
deletes regular files named `checkpoint-*` / `batch-parquet-*` whose last-modified time is older
than `delta.parquet.scratch-orphan-age-seconds` (default 4 hours). The age is not derived from
`lease-seconds`: a live build renews its lease, and completed-batch files are created before
replay starts, so a live file can legitimately outlive the lease. Foreign names and younger files
are left alone so a sibling replica writing into the same volume is not disturbed. Where no such
sibling can exist — a pod-private `emptyDir`, declared with
`delta.parquet.scratch-private-to-pod` (issue #141) — the sweep also drops anything older than the
running JVM, so a container restart mid-build does not leave one file per claimed table on the
volume for the whole age window.

`max-temp-bytes` bounds one file, and a batch build opens one per claimed table at once with
`max-concurrent` builds in flight, so the *directory* is bounded by the deployment rather than by
this key: on GKE `temp-dir` points at a `parquet-scratch` `emptyDir` whose `sizeLimit` sits inside
the container's `ephemeral-storage` request/limit (issue #131). Crossing that is a kubelet eviction,
not an `ABANDONED` artifact. Since issue #138 the deployment sets this key (and the two checkpoint
ceilings) to a third of that `sizeLimit` in `k8s/base/configmap.yaml`, so a single runaway artifact
is abandoned before the volume fills; the application default stays at 10 GiB. Lowering it shrinks
the peak roughly in proportion but never bounds it — one file per claimed table is a multiplier no
per-file key can cap, which is issue **#150**. The budget, its worst case and how to recompute it
live in `docs/delta-client-v2-guide.md` ("Sizing note") — one place, so the numbers cannot drift.

`ABANDONED` is a distinct status precisely because `FAILED` is not terminal: a row still holding
attempts is work in progress, and the download must say so rather than claim the file is missing.

`delta.batch-parquet.duration` retains its metric name but now times one batch-level grouped build,
including shared replay and per-table uploads, rather than one table replay. The same meter also
exports inner `{phase=download|decode|decimal_scan|write|upload}` samples plus `{phase=total}`
for the whole claim (042, issue #111). Prometheus requires that single label set — there is no
untagged series.

## Operations and recovery

Current queue depth is exported as `delta.batch-parquet.queue{status=...}` for all five durable
states (`pending`, `building`, `ready`, `failed`, `abandoned`). Each Prometheus scrape runs a
single grouped database count, shared by all status gauges for at most five seconds; the gauges are
not reconstructed from process-local event counters.
Useful alerts include sustained `pending` growth, `building` rows that survive longer than the
configured lease, and any increase in `abandoned`.

Two ROLE_ADMIN routes provide the supported runbook:

- `GET /api/v1/sites/{siteId}/delta/batches/{batchId}/parquet-artifacts` lists table name, status,
  attempts, last error, and timestamps. It does not expose S3 keys, checksums, claim tokens, or the
  optimistic-lock version.
- `POST /api/v1/sites/{siteId}/delta/batches/{batchId}/parquet-artifacts/{artifactId}/requeue`
  resets `ABANDONED`, or `BUILDING` after its lease expired, to `PENDING`. Attempts become zero and
  the claim token, last error, and unpublished metadata are cleared. `PENDING`, `FAILED`, `READY`,
  and a live `BUILDING` claim return `409`; a route mismatch returns `404`.

Recovery locks the manifest row, so it serializes with worker settlement. A worker displaced from
an expired build cannot renew or publish after the reset because its old claim token no longer
matches. The committed reset is stored as `BATCH_PARQUET_REQUEUE` in `admin_action_logs`, with the
site/account, artifact, batch, table, previous status, caller IP, and user agent.

## Compatibility

There is no Delta protobuf, owner/client, or download behavior change. Snapshot sealing and
continuous-session segment publication are unchanged. Unified artifacts are finalized only when
the owning batch closes. Authorization and short-lived presigned downloads retain the existing
route and DTO. V50 only widens the admin audit action constraint for the recovery event.
Existing READY rows keep their recorded root-level stable keys and remain downloadable and
cleanable without a migration; `s3_key` is treated as opaque metadata.

## Lifecycle

Batch retention and explicit batch deletion keep each recorded `s3_key` (new attempt key or legacy
stable key), retain legacy derivation as a null-key fallback, and paginate the complete
`egress/{siteId}/batches/{batchId}/` prefix. The prefix catches uploads whose process died after
`PutObject` but before manifest publication. Explicit admin deletion routes artifact rows,
changelog segments, and the parent batch through one transactional application service, so a
failed parent delete rolls the dependent-row changes back; prefix enumeration and irreversible
object cleanup start only after that transaction commits. Listing/deletion remain best effort and
fall back to exact recorded keys. A site-history wipe retains its paginated walk of the complete
`egress/{siteId}/` prefix, which removes realtime segment egress and every unified attempt orphan.

## Download readiness contract

The existing owner route is unchanged. It resolves the exact `(site, batch, table)` manifest and
returns the existing short-lived presigned-download DTO only for `READY`. `PENDING`, `BUILDING` and
`FAILED` all return `409 Conflict` — each means "an attempt is queued or running", and answering
`404` for a retryable failure would tell the user the table has no file minutes before the same
click starts working. Only an absent row or `ABANDONED` returns `404 Not Found`.

Manifest rows are written in a new transaction after a batch commits, so a manifest insert can no
longer roll back a successful ingestion transaction or disappear into the publisher's completed
transaction. Callback failures are contained; lazy backfill is the recovery path. Batches that
closed before this change shipped have none — and V49 deliberately does not
backfill them at migration time, which would queue every
historical batch at once and push freshly completed sessions behind the whole backlog. Instead the
download path backfills lazily: a request for a batch with no manifest row enqueues that batch's
tables from its (untouched) raw segments and answers `409`, and the next click downloads the built
artifact. A batch with no published segments still answers `404`, so nothing that was 404 before
becomes a queued build.

Segments created before V32 may have `stats IS NULL`. Backfill discovers their table names by
streaming those raw records, and finalization skips only the expected-row-count assertion when any
segment's count is unknowable; it still writes and publishes the complete replay.

The backfill only accepts a batch in a **terminal** status. A continuous session publishes
non-provisional segments while its batch is still `IN_PROGRESS`, so enqueueing then would build an
artifact from the seals so far and publish it `READY`; batch completion skips tables that already
have a row, and the finished session would serve a silently truncated download. Batch Detail aggregates
segment statistics by table, so rolling deployments and legacy duplicate DTO entries still render
one row and one request per table.

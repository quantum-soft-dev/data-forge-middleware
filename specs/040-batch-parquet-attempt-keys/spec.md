# 040 — Batch Parquet attempt keys

**Issue:** #100  
**Branch:** `plissb/issue-100`  
**Status:** In progress

## Problem

Every finalization claim for one logical `(site, batch, table)` currently uploads to the same
stable S3 key. A claim token prevents a worker whose lease was taken over from publishing stale
manifest metadata, but it cannot cancel that worker's already-running `PutObject`. If the old
upload finishes after the successor, it overwrites the successor's physical object while the
manifest still describes the successor's size and checksum.

## User scenarios and testing

### US1 — A lease takeover publishes one internally consistent artifact (P1)

As a user downloading a completed-batch Parquet file, I receive the exact bytes produced by the
claim whose metadata won publication, even when a superseded upload completes later.

**Independent test:** Pause the first claim during upload, expire and reclaim its lease, let the
second claim upload and publish, then make the old upload physically different and finish it last.
The READY manifest key must still download the successor's bytes and its recorded size/checksum
must match those bytes.

**Acceptance scenarios:**

1. **Given** two claim tokens for one logical artifact, **when** both upload, **then** they write
   different object keys under the same batch prefix.
2. **Given** the successor publishes first, **when** the superseded upload completes last,
   **then** the manifest remains bound to the successor key and metadata.
3. **Given** a stale attempt reaches publication, **when** its token no longer owns the row,
   **then** its attempt object is deleted best-effort without touching the winner.

### US2 — Lifecycle cleanup reclaims unpublished attempts (P1)

As an operator, I can rely on batch retention, explicit batch deletion, and site-history wipe to
find attempt objects even when a process died after upload and before publication.

**Independent test:** Place an unreferenced attempt object below an eligible batch prefix, run the
batch lifecycle cleanup, and verify the object is removed together with the manifest and batch.

**Acceptance scenarios:**

1. **Given** an unpublished attempt object below a live batch prefix, **when** retention or explicit
   batch deletion removes the batch, **then** every object under that batch prefix is enumerated and
   removed.
2. **Given** an unpublished attempt object below a site egress prefix, **when** site history is
   wiped, **then** the existing paginated site-prefix cleanup removes it.
3. **Given** prefix enumeration fails, **when** lifecycle cleanup continues, **then** recorded or
   legacy-derived keys are still deleted best-effort and the database deletion is not rolled back.

### US3 — Existing stable-key manifests remain compatible (P1)

As a user with artifacts created before this change, I can still download and clean them without a
data migration or deployment pause.

**Independent test:** Persist a READY manifest with the legacy stable key and verify download and
lifecycle cleanup continue to use that recorded key.

## Edge cases

- A row is removed by retention or site wipe while an upload is running: the late publisher sees
  the missing row and deletes its own attempt key.
- A process dies after `PutObject` and before publication: the object remains under the deterministic
  site/batch prefix for the next lifecycle cleanup.
- An operator requeues an artifact and resets its attempt count: the new random claim token still
  produces a fresh key, so it cannot collide with a pre-reset upload.
- A table name contains spaces, slashes, or percent signs: the existing URL-encoding policy remains
  unchanged within the attempt-key suffix.
- A stale-key legacy row has a null or non-READY manifest key: cleanup retains the legacy derived-key
  fallback while also enumerating the batch prefix.

## Requirements

- **FR-001:** Every new batch-Parquet upload MUST use an object key unique to its claim token.
- **FR-002:** Attempt keys MUST remain grouped under a deterministic site and batch prefix.
- **FR-003:** The finalizer MUST persist the exact successful attempt key in `s3_key` only while the
  same claim token still owns the manifest row.
- **FR-004:** A superseded publisher MUST never delete or overwrite the current claim's object.
- **FR-005:** A superseded publisher that still owns an uploaded attempt object MUST delete that
  object best-effort after detecting the lost claim.
- **FR-006:** Batch retention and explicit batch deletion MUST enumerate the complete batch prefix
  so an object with no manifest reference is discoverable.
- **FR-007:** Site-history wipe MUST continue to enumerate the complete site egress prefix.
- **FR-008:** Prefix-list or object-delete failures MUST remain best-effort cleanup failures; they
  MUST NOT restore or invalidate database deletions that already committed.
- **FR-009:** Existing manifest `s3_key` values using the stable layout MUST remain valid for
  download and cleanup without a database rewrite.
- **FR-010:** The REST, gRPC, DTO, metric, configuration, and database schema contracts MUST remain
  unchanged.

## Key entities

- **Batch Parquet artifact:** The durable queue/manifest row for one logical batch table; `s3_key`
  becomes the exact immutable object selected by the winning claim.
- **Claim attempt object:** A physical Parquet object identified by the manifest claim token and
  nested below the logical batch prefix.
- **Batch prefix:** The deterministic `egress/{siteId}/batches/{batchId}/` namespace containing
  legacy stable objects and all new attempt objects for lifecycle discovery.

## Success criteria

- **SC-001:** The forced takeover integration test passes when the old upload completes last with
  physically different bytes.
- **SC-002:** The downloaded READY object has byte length and SHA-256 equal to the manifest's
  `file_size` and `checksum` after the takeover race.
- **SC-003:** Batch retention and explicit deletion tests remove both a legacy stable object and an
  unreferenced attempt object under the same batch prefix.
- **SC-004:** Existing download/retention/site-wipe tests remain green without a Flyway migration.

## Clarifications

### Session 2026-08-01

- The issue explicitly permits lifecycle reclamation instead of requiring a new continuously
  scheduled reaper. This feature therefore extends the existing retention/deletion prefix cleanup
  and preserves the existing site-wipe prefix walk.
- Compatibility is achieved by treating `s3_key` as opaque recorded metadata. New writes change
  layout; existing rows are neither rewritten nor re-derived for download.

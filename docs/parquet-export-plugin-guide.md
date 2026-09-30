# Parquet Export Plugin Guide (028)

The `parquet-export` plugin gives an external client pull-based access to the Parquet files
produced by the Delta v2 pipeline. The **default** listing is one completed-batch file per
table (`type=batch`). Per-segment **delta** files and full **checkpoint** snapshots remain
available behind explicit `type` filters.

Listing is protected by HTTP Basic Auth; each downloadable file comes with a **registered
one-time download link** that redirects once to a short-lived S3 presigned URL and then dies.

## Why one-time links

S3 presigned URLs are stateless signatures — S3 cannot enforce single use. The middleware
registers each link as a `download_links` row and consumes it with an atomic
`UPDATE … WHERE consumed_at IS NULL AND expires_at > now()`: under any concurrency exactly one
request wins the 302 redirect; everyone else gets **410 Gone**. The presigned URL minted after a
won consume lives ~60 seconds — the residual exposure window.

## Activation & credentials

1. The account owner activates the plugin through the standard plugin API:

   ```bash
   curl -X POST https://host/api/v1/plugins/parquet-export/activate \
     -H "Authorization: Bearer $AUTH0_TOKEN" -H "Content-Type: application/json" \
     -d '{"pluginData":{}}'
   ```

   The response's `apiKey` field carries the Basic Auth credentials **exactly once**, in
   `login:password` form (e.g. `pex_Ab3xY9Qm2Lk4:Kf82…`). The login is stable; the password is
   stored only as a BCrypt hash and cannot be retrieved again.

2. Re-activating an already-active plugin is idempotent and never re-shows or rotates the
   credentials. Reactivation after a deactivation keeps the same login.

3. Rotation (owner API, Auth0):

   ```bash
   curl -X POST https://host/api/v1/account/plugins/parquet-export/rotate-password \
     -H "Authorization: Bearer $AUTH0_TOKEN"
   ```

   Returns `{login, password}` once; the old password stops authenticating immediately.
   Already-registered links are credential-independent and stay valid.

4. The same two operations exist in the UI — Dashboard → **My Plugins** → *Plugins* tab. An
   activation that issued credentials opens a one-shot dialog with the login and the password
   as separate copyable fields, and an active plugin card carries a **Rotate password** action
   that reissues them through the same dialog. Closing the dialog discards the value: the
   browser never stores it, and the only way back is another rotation.

## Listing files

```
GET /api/v1/plugins/parquet-export/files
Authorization: Basic base64(login:password)
```

| Param | Meaning | Default |
|---|---|---|
| `since` | ISO 8601 datetime (offsets like `Z`/`+02:00` accepted, normalized to UTC); only files **produced after** this moment | epoch |
| `siteId` | limit to one site (must belong to the account) | all sites |
| `table` | table-name filter | all tables |
| `type` | `batch` \| `delta` \| `checkpoint` | **`batch`** |
| `cursor` | opaque keyset cursor — the previous response's `nextCursor` | start |
| `size` | page size ≤ 100 | 50 |

> **Breaking change (041 / #109).** A request with no `type` used to return `delta` +
> `checkpoint`. It now returns **only batch files**. Existing integrations that still consume
> per-segment files **must add `type=delta`**. Checkpoint consumers must send
> `type=checkpoint`. There is no `type=all`.

`producedAt` for batch files is the artifact's `ready_at` (or `updated_at` if the row is
`abandoned`). For delta files it is the segment's `egress_at`; for checkpoints, `updated_at`.
The `since` filter is strictly greater-than. A late artifact that finishes after retries — or
that an operator requeues and that later becomes `READY` — appears with a new `ready_at` and
will show up in the next sweep. `ready_at` / `updated_at` are stamped inside a short serialized
DB publication, so they cannot land before a sibling that already committed.

**Sync protocol (mandatory reading):**

1. **One sweep = one `since`.** Call with a fixed `since`, then keep passing the returned
   `nextCursor` until `hasMore` is `false`. Only after the sweep completes may you advance
   `since` to the max `producedAt` you observed. Never bump `since` between pages: many files
   share one `producedAt` (a segment fans out to one file per table), and a mid-sweep bump
   would silently skip the rest of that timestamp.
2. **Drive iteration by `hasMore`, not by an empty list.** A page can contain fewer than
   `size` entries — even zero — while `hasMore` is still `true` (delta candidates whose Parquet
   the egress skipped are dropped after pagination). The cursor advances past them.
3. **Always pass `since` in steady state.** `since` defaults to epoch; the server paginates in
   SQL so an unbounded sweep is safe, but it makes the client re-walk (and re-register links
   for) the entire history every time. Epoch is for the initial backfill only.

Response (one entry per file; a fresh one-time link is registered per **downloadable** entry):

```json
{
  "files": [
    {
      "siteId": "…", "siteDomain": "shop.example.com",
      "table": "orders", "type": "batch",
      "batchId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "artifactId": "0195aaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
      "status": "ready",
      "sessionMode": "delta",
      "firstSeq": 100, "lastSeq": 250, "seq": null,
      "producedAt": "2026-07-27T10:16:00",
      "fileName": "orders_batcha1b2c3d4-e5f6-7890-abcd-ef1234567890.parquet",
      "downloadUrl": "https://host/api/v1/plugins/parquet-export/download/<43-char-token>",
      "linkExpiresAt": "2026-07-27T11:16:00"
    }
  ],
  "size": 50, "hasMore": false, "nextCursor": null
}
```

Batch rows also carry `batchId`, `artifactId`, `status` (`ready` or `abandoned`) and
`sessionMode` — the kind of session the batch was: `full_snapshot` (the whole site, replacing it),
`delta` or `continuous`. `sessionMode` is null for `type=delta` and `type=checkpoint` files and for
a batch started before the server recorded modes (V47). The values are lower-case on this
endpoint, like `type` and `status` — the owner/admin Delta Sync API spells the same modes in
upper case (`FULL_SNAPSHOT`), so do not share a case-sensitive enum between the two. An
`abandoned` table exhausted its build attempts: `downloadUrl` and `linkExpiresAt` are null
and no `download_links` row is created. Alert and ask an operator to
`POST /api/v1/sites/{siteId}/delta/batches/{batchId}/parquet-artifacts/{artifactId}/requeue`
using the `artifactId` from this listing.
`PENDING` / `BUILDING` / `FAILED` rows are omitted; they appear later as `ready` (or
`abandoned`).

### Order, epochs and what to skip (`type=batch`, issue #378)

**The order the server guarantees.** Within one site, batch files are listed in **batch order**:
a batch's tables get their `producedAt` only after every table of every earlier batch of that
site is `ready` or `abandoned`. Walking the listing by `producedAt` + `nextCursor` therefore
delivers batch N before batch N+1 — a quick delta can no longer overtake the large snapshot
before it. What is **not** guaranteed:

- **One batch in one sweep.** A batch's tables are published one by one, so a sweep can see only
  part of a batch. The rest arrives in a later sweep with a later `producedAt` — but never after
  a file of a later batch of the same site.
- **An old batch returning late.** An operator's requeue of an `abandoned` artifact, or a batch
  built on demand long after it finished, is listed after its successors. See `abandoned` below.
- **A batch whose build was never queued.** A batch's work rows are created right after its
  session commits; if that step is lost (the process dies at that instant), the batch holds no
  place in the queue, later batches are built past it, and it appears only if something builds it
  later (an owner download does). This is a known gap of the queue, not of this listing
  (#380).
- **Order across sites.** Each site is ordered on its own.
- **`type=delta` / `type=checkpoint`.** Unchanged; this section is about batch files.

**The watermark is a pair (epoch, seq), not one seq.** A site's seq does not only grow: a history
wipe restarts it at 1, and a client that lost its journal may resend a snapshot from seq 1. A
consumer that compares `lastSeq` alone skips everything after such a restart. So:

1. **A `full_snapshot` opens a new epoch.** The first file you see of a *new* `batchId` with
   `sessionMode = full_snapshot` means: clear **all** tables of that site, start the seq count
   inside this batch, and apply this file and every later file of the same `batchId` as they
   arrive (they may come in later sweeps). A table that gets no file from the snapshot stays
   empty — correct: an empty or dropped source table produces none. *New* means a `batchId` you
   have never seen, as `ready` or as `abandoned`: keep the set of snapshot `batchId`s seen per
   site. A requeued old snapshot returning `ready` after its successors has been seen before, as
   `abandoned`, and must not clear the site again.
2. **A `full_snapshot` is never skipped by `lastSeq`.**
3. **Within an epoch, `lastSeq <= applied_seq → skip`** — for downloadable rows only
   (`status=ready`). `lastSeq == null` means the range is unknown — **never skip** that file (in
   JavaScript `null <= n` is `true`).
4. **`status=abandoned` means the chain is broken**, not "stop for ever". Always surface it as an
   alert (it is not a file the watermark can swallow, even when sibling ready tables cover the same
   `lastSeq`), stop applying the site's deltas, and wait for the next `full_snapshot` — or reload
   the site from `type=checkpoint`. An operator's requeue of that artifact repairs the owner's
   download, but it returns *after* its successors and does not re-thread a chain the consumer has
   already stopped; resume at a snapshot. Abandoned rows keep a stored range when one was
   published; they do not fall back to live changelog segments.

The same three situations, as a consumer sees them:

| Situation | What the listing shows | What the consumer does |
|---|---|---|
| **Re-baseline** (the server asked for one, or the client decided): seq continues | The old batch's tables not yet built when the snapshot committed as `abandoned`, **then** the snapshot's tables as `ready`, `sessionMode = full_snapshot`, seq above the old | Alert and stop on `abandoned`; on the `full_snapshot` clear the site, apply it, resume. Rows deleted at the source are gone, because the tables were cleared |
| **History wipe** (#89): seq restarts at 1 | The old batches are gone from the listing (the wipe deletes them). The new snapshot arrives with `sessionMode = full_snapshot` and `firstSeq = 1`, far below your `applied_seq` | Rule 2: do not skip it. Clear the site, apply, and compare later deltas inside the new epoch |
| **Client lost its journal** and resends a `FULL_SNAPSHOT` from seq 1 without a wipe | Same as the wipe, except older batches stay listed behind you | Same as the wipe. Stay on `type=batch`: a `type=delta` file name is built from its seq range, so a restarted range reuses the old names and an old file is overwritten — a `type=delta` consumer cannot tell the two epochs apart |

A consumer that already skipped a snapshot under the old `lastSeq`-only rule recovers by reloading
the site's tables from `type=checkpoint`.

`nextCursor` is non-null exactly when `hasMore` is `true`.

Errors: `400` malformed filters or cursor, `401` + `WWW-Authenticate: Basic` on bad credentials
or a deactivated plugin, `429` + `Retry-After` on the per-account rate limit.

> **Note:** the rate limit is the shared per-account plugin bucket (Bucket4j, 100 req/min) —
> an account that also uses the Bit BI plugin API shares this budget across both plugins.

## Downloading

```
GET /api/v1/plugins/parquet-export/download/{token}     ← no authentication
```

- **302 Found** — first use; `Location` is an S3 presigned URL valid ~60 s. The link is consumed
  atomically at this moment.
- **410 Gone** — link already consumed, expired (1 h after registration by default), or the
  plugin was deactivated.
- **404 Not Found** — unknown (or purged) token.

`curl -L -o file.parquet <downloadUrl>` follows the redirect in one step.

Edge case: if the S3 object was deleted between listing and download, the redirect still happens
and S3 answers 404 — the link is consumed; re-list to get a fresh entry.

## Housekeeping

A scheduled job deletes consumed/expired `download_links` rows older than the retention window.

## Trade-offs of the batch default

- **Latency.** The default listing shows a table only after `SessionEnd` and the batch-Parquet
  worker has published `READY` (or `ABANDONED`). The worker is woken by `AFTER_COMMIT`
  `BatchCompletedEvent` (enqueue + wake), a 60 s sweep, and owner-download backfill. The
  catalog does **not** enqueue missing history on list. Per-segment files appear seconds after
  a seal. A site that holds a continuous session open for hours will wait until that session
  ends; those clients should stay on `type=delta`.
- **Retention.** Batch artifacts are deleted with the batch. The client poll interval must be
  shorter than the retention window, or unread files disappear.
- **Checkpoints.** A snapshot batch already yields one file per table, so the first batch
  after activation is enough for a primary load. `type=checkpoint` remains for emergency
  rebuilds.

## File columns

Every file type — `batch`, `delta` and `checkpoint` — carries the site's declared columns, and
`batch`/`delta` files carry the service columns `_op`, `_seq` and `_changed` in front of them. Since
issue #369 **every** file also ends with **`_row_hash`**: the source client's identifier of the row
within its table, as 64 lowercase hex characters, or null when the client did not send one. In a
`batch` or `delta` file an `INSERT` and the `DELETE` of the same row carry the same value and an
`UPDATE` carries none; in a `checkpoint` snapshot it is the hash of the record that created the row.
Expect nulls for rows written before the source client started sending the field — the `INSERT` of
such a row is null while its later `DELETE` may carry a hash.

**`_row_hash` is not a unique key.** In the source client's `track_duplicates` mode a row present
several times in a keyless table goes out as several `INSERT` rows of a `batch` or `delta` file with
one hash (and a change in its multiplicity as several `INSERT`s or `DELETE`s with that hash), so
deduplicating or collapsing rows by `_row_hash` drops copies that still exist at the source. The
server does not rely on its uniqueness either. Do not use it as a primary key, a join key that
assumes one row per value, or a `DISTINCT`/`GROUP BY` to remove duplicates; treat a null as
"unknown", never as a value shared by the rows that have it.

It is the **last** column by design (the owner's decision on #373): a reader that maps columns by
name must tolerate this extra `_`-prefixed column; one that maps by position is unaffected. See
`docs/delta-client-v2-guide.md` ("`row_hash`").

## Operational notes

- **Anonymous download route**: `/download/{token}` has no application-level rate limit (the
  token is the credential; unknown tokens cost one indexed lookup and increment the
  `plugin.parquet.export.download.rejected{reason="unknown"}` Micrometer counter, with no audit
  write). For internet-facing deployments add ingress/WAF rate limiting on
  `/api/v1/plugins/parquet-export/download/**`.
- **`plugins.parquet-export.enabled=false`** only unregisters the plugin SPI bean — new
  activations become impossible, but existing activations keep authenticating and the
  `/files`, `/download` and rotate-password endpoints stay live. To cut off a specific client,
  deactivate its plugin (or rotate the password).
- **Login is a public identifier**: lookup happens before the BCrypt check, so response timing
  distinguishes known from unknown logins. This is by design — the secret is the password.
- **Tokens appear in URL paths**: `/download/{token}` shows up in ingress/proxy access logs.
  Single use + the 1 h TTL bound the exposure, but treat those logs accordingly (a logged
  token is dead after the first download and after expiry).

## Configuration (`application.yml`)

```yaml
plugin:
  parquet-export:
    link-ttl-seconds: 3600      # one-time link validity before first use
    presign-ttl-seconds: 60     # S3 presigned URL validity after consume
    purge-retention-days: 7     # keep consumed/expired rows this long
    purge-interval-ms: 3600000  # purge sweep cadence
    base-url: ""                # absolute prefix for download URLs; empty = derive from request.
                                # REQUIRED behind a proxy/ingress: without forwarded-headers
                                # handling the derived URL is the pod-local scheme/host.
plugins:
  parquet-export:
    enabled: true               # component toggle (matchIfMissing = true)
```

## Implementation map

- Security: dedicated filter chain (`SecurityConfiguration.parquetExportPluginApiFilterChain`,
  Order 4) — Basic Auth on `/files` via `ParquetExportBasicAuthFilter`, anonymous `/download/**`;
  denyAll carve-outs in the OAuth2 catch-all.
- Credentials: `ParquetExportCredentialsService` — `plugin_data = {login, passwordHash}` (BCrypt),
  login-first lookup = one BCrypt comparison per request.
- Catalog: `ParquetExportFileService` + `ParquetExportCatalogDao` — default source is
  `batch_parquet_artifacts` (`READY`/`ABANDONED`); `type=delta` still fans out
  `jsonb_object_keys(stats)`. Keyset pagination `(producedAt, s3Key)` runs in SQL (bounded to
  `size + 1` rows); the `deltaPresence` probe runs only for served delta pages, and drops a row
  only on a **known** absence — a read S3 refused to answer keeps the entry rather than hiding a
  file that is there (issue #157).
- Links: `DownloadLink` / `download_links` (V39), `DownloadLinkService` (atomic consume + 60 s
  presign), `DownloadLinkPurgeScheduler`.
- Audit: `PluginAuditService` — `FILES_LISTED`, `LINK_CONSUMED`, `LINK_REJECTED`,
  `PASSWORD_ROTATED` (plus the standard activation events).
- Spec & design: `specs/028-parquet-export-plugin/`.

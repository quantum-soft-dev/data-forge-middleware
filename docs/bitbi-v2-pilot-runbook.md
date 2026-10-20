# Runbook: Bit BI v2 pilot on one site

**Issue:** #389 (phase 3 of [`docs/cr-bitbi-parquet-plugin.md`](cr-bitbi-parquet-plugin.md), ticket E).
**Exit criterion:** seven consecutive nights in which BitBi's v2 mirror of the site and its v1 mirror
on this server show no **unexplained** difference, the result written in #389, and BitBi's written
confirmation.
**What this document is not:** a description of the API. The routes, bodies and status codes are in
[`docs/bitbi-integration.md`](bitbi-integration.md) ("Bit BI API v2"); this runbook says what to
check, where, and what to do when something is off.

Everything here is written against what #386, #387 and #388 shipped: `/v2/sites`, `/state`,
`/schema`, `/snapshot`, `/files/{fileId}` and `/changes` with its `epoch` parameter and per-batch
table manifest (V61). The commands below are read-only; the deploy, the message to BitBi and the
nightly verdict are a person's steps.

## The site

| | Pilot | For a later site (phase 4) |
|---|---|---|
| Site | `fyt-new` | the moved site's name |
| Site id | `c6e351ce-faff-4fb5-8cec-8354bcb50b65` | from the admin UI |
| Host | `test.dfm.bitbi.io` (overlay `k8s/overlays/dev`, cluster `dev-bitbi-cluster`, project `bitbi-dev`, namespace `forge`) | the same server (CR decision 10) |
| Source | DBF, every table `identity: row_hash` | DBF |
| Comparison | v2 mirror against v1 mirror, both fed by this server | the same |

The comparison is v2 against v1 **on this server**, not against BitBi's old FYT mirror, which has
been stale since 2026-07-26 (CR, plan, phase 3). Both mirrors belong to the same `bit-bi`
activation and the same API key: v2 has no activation of its own and no mode (CR decision 1).

Below, `SITE` is the site id and `KEY` the activation's `X-Plugin-Api-Key`. The key belongs to BitBi;
on our side the admin REST routes and the admin UI are used instead, with an Auth0 admin token.

## 1. Before the start

Each item is checked and its answer written in #389 before BitBi is told to start.

| Check | How | Expected |
|---|---|---|
| v2 is deployed | Without BitBi's key: the image tag of `deploy/forge-backend` is the full 40-character commit sha the deploy workflow was run on (`github.sha` in `app-deploy.yml`; `kubectl -n forge get deploy forge-backend -o jsonpath='{.spec.template.spec.containers[0].image}'`), and it must contain the squash commit of #388's PR — `git merge-base --is-ancestor $(gh pr view 397 --json mergeCommit --jq .mergeCommit.oid) <image sha>` exits 0. Flyway's startup line in the pod log reads `Current version of schema "public": 61` (or `now at version v61`). BitBi can confirm from their side: `GET /api/v1/plugins/bit-bi/v2/sites/SITE/changes` answers **200**, not 404 | the image contains #388; schema at **V61** |
| Row groups are 8 MiB | `k8s/overlays/dev/configmap-patch.yaml` carries `DELTA_PARQUET_ROW_GROUP_BYTES: "8388608"` (#385). It is consumed through `envFrom`, so applying the overlay restarts nothing: check the **running** pod too, `kubectl -n forge exec deploy/forge-backend -- printenv DELTA_PARQUET_ROW_GROUP_BYTES` | `8388608` in both. A pod started before the change still writes 16 MiB groups: `kubectl -n forge rollout restart deploy/forge-backend` |
| The snapshot BitBi will load was written with 8 MiB groups | Download the widest table through `/snapshot` → `/files` — on `fyt-new` that is `nsfwork`, the table that crossed BitBi's limit at 16 MiB (`docs/delta-client-v2-guide.md`, "Metrics", the 8 MiB note) — and read its footer: DuckDB `SELECT max(row_group_bytes), max(row_group_num_rows) FROM parquet_metadata('nsfwork.parquet')` | about 8 MiB or less, and rows per group × columns under BitBi's six million cells. A build that advances the site's pointer — any new segment for the site, whichever table it touched — rewrites **every** table's snapshot, `nsfwork` included; an idle nightly visit (no new segment for the site) rewrites none. So read the file after the first advancing build since the pod restarted (`/state` `checkpointSeq` moved): a 16 MiB reading on `nsfwork` then is a **failed check** — the value did not reach the pod, or the build did not run — not a table that has not caught up yet |
| Every row carries `_row_hash`, unique per table | For every table of `/snapshot`: `SELECT count(*), count(_row_hash), count(DISTINCT _row_hash) FROM 'table.parquet'` | three equal numbers per table. On 2026-10-06: 5 028 074 rows, all hashed, all distinct |
| `track_duplicates` is off | On the FYT machine, the extractor's `config.toml`: `[ingestion.keyless] track_duplicates` absent or `false` (site side; asked of whoever runs the extractor) | off — v2 does not support it (CR, "Row identity") |
| Extractor frequency | Site side, not ours (CR decision 9). BitBi ask for hourly runs | the value set on the site, written down |
| The site has a checkpoint | `GET .../v2/sites/SITE/state` (BitBi) or the site's Delta Sync tab (`/admin/sites/SITE`) | `checkpointSeq` above 0, `schemaVersion` above 0 |
| The epoch BitBi will start in | `/state` `epoch` | written down: an unplanned change during the pilot is the first thing to look for (section 4.2) |

**A table without the `_row_hash` column, or with fewer hashes than rows**, holds rows written before
the client sent the field (#369). A `row_hash` consumer cannot delete such a row, so the site needs
one re-baseline before the pilot (CR decision 8). That is an operator decision with a cost — the
whole site is re-sent and Bit BI v1 re-captures its baselines — and it is taken by a person, not
by this runbook.

**What BitBi run** (their side, for the record): bootstrap from `/snapshot` at `(epoch,
checkpointSeq)`, then `/changes?epoch=<epoch>&afterSeq=<seq>` in a loop, applying batches by
`_row_hash`, beside their unchanged v1 consumer (`/files` + `/sql-changes`). Two facts worth
telling them again before the start:

- **One rate-limit bucket.** v1 and v2 share 100 requests a minute per account, and every `/files`
  call spends a token. A bootstrap of the site's ~87 tables plus v1's polling can hit `429`; the
  consumer must honour `Retry-After`.
- **A presigned URL lives 60 seconds.** Follow the `302` immediately; keep the `fileId`, not the URL.

## 2. Deploy and the message to BitBi (person)

1. Tag the deploy: `TAG=deploy-dev/$(date +%Y%m%d-%H%M); git tag $TAG && git push origin $TAG`
   (`docs/cr-tag-driven-dev-deploy.md`), from a `develop` commit that contains #388.
2. Wait for the rollout and repeat the first row of section 1.
3. Tell BitBi: the host, the site id, the epoch and `checkpointSeq` from `/state`, the date of
   night 1 (the first night after their bootstrap), and where the result is recorded (#389).

Night 1 is the first 02:00 UTC build after BitBi's v2 bootstrap; the morning check of night N reads
everything between the morning of N−1 and the morning of N.

## 3. Every morning, seven times

### 3.1 Our side

**No meter here carries a site tag**, and the dev server holds other sites besides the pilot. A
number that moved says *something* happened; the log line it comes with names the site and the
table. Read the metrics in the console's Managed Service for Prometheus page (PromQL, exported
names — `deploy/gke/README.md`, "Metrics scraping (dev)"; `docs/delta-client-v2-guide.md`,
"Metrics", for the full mapping). Two kinds, two aggregations:

- **Counters** (`*_total`) count what *this pod* did. They reset when a pod restarts; `increase()`
  copes with that, so use `sum(increase(...))` across pods.
- **The two database-backed gauges** — `delta_batch_parquet_queue` and
  `delta_checkpoint_tables_given_up` — are a cached count of rows in the shared database, so every
  pod reports the **same server-wide number**. `sum` would multiply it by the replica count (the dev
  HPA runs 1 to 3) and make it move whenever the pod count does. Use `max`. Server-wide also means
  every site's rows are in it, and the old-epoch artifacts a re-baseline left `ABANDONED` (#244)
  too.

| What | PromQL | Healthy | When it is not |
|---|---|---|---|
| Abandoned batch files | `max(delta_batch_parquet_queue{status="abandoned"})` | 0, or unchanged since yesterday and already explained — old-epoch artifacts a re-baseline left `ABANDONED` (#244) are legitimate and stay | a rise: find the batch by the WARN/ERROR line, then section 4.1 |
| Batch files stuck in retry | `max(delta_batch_parquet_queue{status="failed"})` | 0 by the morning | a `FAILED` row retries for about an hour, then becomes `ABANDONED`; until then the v2 feed waits before that batch (`/changes` ends before it) |
| Lost enqueues recovered | `sum(increase(delta_batch_parquet_enqueue_recovered_total[24h]))` | 0 | not a divergence by itself (#380 recovered the batch, in order); write it down |
| Whole checkpoint builds aborted | `sum by (reason) (increase(delta_checkpoint_builds_aborted_total[24h]))` | 0 | `/snapshot` stays at the previous `checkpointSeq`; the changes above it are retained, so a running consumer is not affected, but a reload is. `reason` names the cause (`docs/delta-client-v2-guide.md`) |
| Checkpoint behind ingestion | `max(max_over_time(delta_seq_lag_max[24h]))` (recorded at each commit, all sites); the pilot site's own lag is on its Delta Sync tab | bounded by about a day of changes, not growing from one morning to the next | growing for days: the build is not passing the site. The reason is in the site's **WARN** lines (below) — `Checkpoint build failed for site SITE: …` for every build that threw (the four counted aborts included; they also log an ERROR of their own), and `Skipping site SITE this tick: …` when S3 would not say whether the seed frame exists — and, for the four permanent kinds only, in `delta_checkpoint_builds_aborted_total{reason}`. **Not** on the Delta Sync tab, which records a build's abort only while a site has no checkpoint at all (pointer 0), and this site has one |
| Builds deferred behind another build | `sum(increase(delta_checkpoint_builds_deferred_total[24h]))` | 0 | a site's build waited out the fold budget (#178). It is retried once more at the end of the same tick, and only if that retry is deferred too does it wait for the next night — so one site can add **2** in one night. `Deferring site SITE this tick: …` (WARN) names it; if the pilot site was deferred on both passes, its `/snapshot` is a day older |
| S3 would not say whether an object exists | `sum(increase(delta_s3_read_denied_total[24h]))` | 0 | a read denial S3 would not resolve (#157): a build skipped with `Skipping site SITE this tick` (WARN), not counted on `builds_aborted`. Many sites at once is a bucket-policy or IAM problem, not the site |
| Tables given up | `max(delta_checkpoint_tables_given_up)` | 0 | `/snapshot` lists such a table as `missing` |
| Decimals written NULL in Parquet | `sum by (reason) (increase(delta_parquet_unrepresentable_decimals_total[24h]))` | 0 | `overflow`, `non_finite`, `malformed` — an **expected** v1/v2 difference for `overflow`, section 4.4 |
| v1 side: SQL generation | `sum(increase(sql_generation_errors_total[24h]))`, `sum(increase(sql_generation_delta_segments_poisoned_total[24h]))`, `sum(increase(sql_generation_delta_records_skipped_unrepresentable_key_total[24h]))` | 0 | a difference caused on the **v1** side, section 4.4. `poisoned` is a **census**: a stuck segment counts again on every retry (about once an hour past the threshold), so 24 a day can be one segment — the ERROR line names it |

**WARN and ERROR lines for the site**, every morning whatever the counters say (the backend logs
JSON on dev). WARN is not optional: the commonest nightly failures — S3, JDBC or scratch errors
inside a checkpoint build — are logged at WARN as `Checkpoint build failed for site SITE: …` and
move no `builds_aborted` series, so an ERROR-only query and a flat counter both read clean:

```bash
gcloud logging read \
  'resource.type="k8s_container" AND resource.labels.namespace_name="forge"
   AND (jsonPayload.level="ERROR" OR jsonPayload.level="WARN")
   AND (jsonPayload.message:"c6e351ce-faff-4fb5-8cec-8354bcb50b65" OR jsonPayload.siteId="c6e351ce-faff-4fb5-8cec-8354bcb50b65")' \
  --project bitbi-dev --freshness=1d --limit=200 \
  --format='value(timestamp,jsonPayload.logger,jsonPayload.message)'
```

Lines worth knowing by their text (from `CheckpointScheduler`): `Checkpoint build failed for site SITE: …`
(WARN — the build threw; the message is the cause), `Skipping site SITE this tick: …` (WARN when S3
would not answer, #157; the same text at INFO is a build discarded by a wipe or a re-baseline, which
is routine), `Deferring site SITE this tick: …` (WARN, fold budget), `Checkpoint retention failed for
site SITE: …` (WARN, the build itself succeeded). A WARN for degraded decimals names the table, one
line per rendered table. A background thread does not always carry the `siteId` MDC key, hence the
message search beside it.

**The site's state**, from the admin UI (`/admin/sites/SITE`, Delta Sync tab) or
`GET /api/v1/sites/SITE/delta/sync-state` with an admin token: the last checkpoint time and seq
(and so the site's own lag), the outcome of the last **forced** rebuild, and whether a re-baseline
or a rebuild is pending. It does **not** say why a nightly build failed for this site: the abort
fields are written only while a site has no checkpoint yet (pointer 0, #224), so for the pilot the
reason is in the WARN lines above (and, for the four permanent kinds, in
`delta_checkpoint_builds_aborted_total{reason}`). Two things on the
tab end the pilot's night without being a v2 defect: a re-baseline someone requested, and a history
wipe — both move the epoch (section 4.2).

### 3.2 BitBi's side

BitBi send, per night, for every table: the row count of the v1 mirror and of the v2 mirror, a
per-table checksum of each, the v2 watermark `(epoch, seq)` they compared at, and the time of the
last v1 generation applied. Ask for those five things in that shape every morning; a difference
without the watermark cannot be placed.

A difference that disappears on the next comparison, and whose rows are in a batch above the v2
watermark or a v1 generation not yet fetched, is **lag**, not divergence: the extractor runs every
hour and the two consumers do not poll at the same instant. Write it down as explained.

### 3.3 Recording the night

One comment in #389 per morning:

```
Night N (YYYY-MM-DD) — clean | explained | NOT CLEAN
Ours: abandoned=… (max), failed=… (max), given-up=… (max), recovered=…, builds.aborted{reason}=…, builds.deferred=…, s3.read-denied=…, WARNs `Checkpoint build failed`/`Skipping site` for the site=…, seq.lag max=…, unrepresentable{reason}=…, v1 sql errors/poisoned/skipped_key=…, ERRORs for the site: none | <lines>
BitBi: watermark (epoch, seq)=…, last v1 generation=…, tables compared=…, differing tables: none | <table: v1 rows / v2 rows, checksum>
Explanation: – | <cause and section of the runbook>
Consecutive clean nights: k/7
```

**A night counts** when every difference has a named cause that is not a defect of v2 (sections
4.3 and 4.4 list the known ones). The issue asks for seven nights **in a row**, and the runbook
reads that strictly:

- **An unexplained difference resets the count to zero**; it starts again on the first clean night
  after its cause is fixed.
- **A lost night** — the v2 consumer halted (section 4.1) and reloaded, or BitBi sent no comparison —
  **also breaks the run**: the count restarts at zero on the next clean night. Nothing was compared
  that night, so the run is not seven in a row.

Whether a lost night may instead be skipped (not counted, not a reset) is a **policy call for the
person running the pilot**, not this runbook's: it would let the pilot pass on seven clean nights
spread over any number of halts. If it is relaxed, write the decision in #389 before it is applied,
and count from then on by the relaxed rule.

After night 7, a summary comment and BitBi's written confirmation quoted in #389 close the ticket.

## 4. When something is off

### 4.1 A batch with a table that is not `ready`

BitBi's consumer stops the site at that batch (CR, consumer protocol, step 5) and reports its
`batchId` and the table. Nothing past it has been applied, so nothing is diverged yet — it is a halt.

- **`abandoned`** — the build gave up. Read why: `GET /api/v1/sites/SITE/delta/batches/{batchId}/parquet-artifacts`
  (admin) gives each table's `status`, `attemptCount` and `lastError`. Fix the cause if it is ours
  (a ceiling too low — `DELTA_BATCH_PARQUET_MAX_TEMP_BYTES`, a scratch directory full — then
  `POST .../parquet-artifacts/{artifactId}/requeue`, issue 039. The requeue is audited and answers
  **409** when the batch's segments are already gone (#244) or a live attempt still holds the row.
  After a successful requeue the batch reappears in the feed once its file is `ready`, and the
  consumer, which stopped there, continues.
- **`missing`** — no file and no build. Three causes: the table has no declared schema (look at
  `/schema`; the client must submit it); the batch was never queued (#380's sweep re-enqueues a lost
  batch within `delta.batch-parquet.enqueue-horizon-hours`, 168 by default, and until then the feed
  ends *before* it rather than listing it `missing`); or the server ended the session after it had
  committed records (every table of the batch is `missing`, CR decision 25).
- **When there is no file to produce** — a 409 on requeue, a `missing` the client cannot repair —
  the consumer waits for the nightly checkpoint to pass the batch (`/state` `checkpointSeq` ≥ its
  `lastSeq`) and reloads the site from `/snapshot`. That is a **lost night** (section 3.3: it breaks the
  run unless the person running the pilot has relaxed that rule), not an unexplained difference.

### 4.2 A missed `full_snapshot`, or an epoch that moved

The epoch moves on a history wipe and on a re-baseline and on nothing else (CR decision 13). A v2
mirror that kept applying across one holds rows of a discarded history.

1. Compare BitBi's watermark epoch with `/state` `epoch`. Different → the mirror must be cleared and
   reloaded (from the `full_snapshot` batch that opened the epoch, or from `/snapshot`); the feed has
   already been answering from the new epoch's start, so a consumer that ignored the top-level
   `epoch` of `/changes` is the defect — theirs.
2. Same epoch → read `/changes?epoch=<epoch>&afterSeq=0` from the start: the epoch's first batch is
   its `full_snapshot`. A consumer that applied it on top of its tables instead of clearing them
   holds duplicates (`count(*) > count(DISTINCT _row_hash)` in the mirror).
3. Who moved it: the site's Delta Sync tab shows a requested re-baseline, a wipe is in the admin
   audit log (`SITE_HISTORY_WIPE`), and the client may also send a `FULL_SNAPSHOT` on its own.

An epoch change during the pilot is a planned event only if someone planned it; otherwise write
down who did it before anything else.

### 4.3 A difference by `_row_hash`

Compare per table, in the mirror and in the server's own file for the same point:
`count(*)`, `count(_row_hash)`, `count(DISTINCT _row_hash)`.

| Symptom | Likely cause | What to do |
|---|---|---|
| v2 has **more** rows, some with a NULL hash | rows written before #369; a `DELETE` by hash cannot reach them | not a v2 defect, but it breaks the pilot's premise: the site needs the re-baseline of section 1 |
| v2 has **more** rows, hashes duplicated | a batch applied twice, or the rows of a `continuous` batch at or below the snapshot's `checkpointSeq` applied after a bootstrap — the first batch after `/snapshot` can start below it, and only `_seq` above it may be taken (`bitbi-integration.md`, rules for applying a batch) | consumer defect; reload the site from `/snapshot` |
| v2 has **fewer** rows than v1 | one `DELETE` removed several rows sharing a hash: `track_duplicates` was turned on, or the mirror already held duplicates | check `config.toml` on the site; reload |
| v2 and v1 agree with each other, both fewer than the DBF | the client collapses identical DBF records into one row (CR, "Row identity") | by design, explained |
| A checksum differs, counts agree | a typed value differs: section 4.4 first | — |

### 4.4 Differences that are not v2 defects

Name the cause in the night's record and the night still counts.

- **A decimal wider than 38 digits** (`unrepresentable_decimals{reason="overflow"}`, #382): Parquet
  writes the cell NULL, the v1 SQL carries the number. The WARN names the table. The remedy is the
  source data or the declared schema; for the pilot it is an explained difference.
- **`non_finite` / `malformed`**: both paths write a numeric column's cell NULL (#215, #240) and should
  agree; a `double precision` `NaN` is kept by both (#233). A difference here is not expected — treat
  it as unexplained.
- **v1 skipped a record** (`sql_generation_delta_records_skipped_unrepresentable_key_total` moved): a
  v1 `UPDATE`/`DELETE` keyed by a decimal it cannot address was not rendered, so the **v1** mirror
  keeps a row the source changed or deleted.
- **v1 mirror older than a v1 fix**: a v1 mirror built before #370 can hold rows a `DELETE` with a
  NULL key never reached; a batch whose SQL was deleted and generated again is re-delivered to a
  cursor that had passed it. Either way the v1 side is wrong and its repair is `reinit`.
- **Lag** (section 3.2).

Anything else is unexplained: the night is not clean, and the difference is filed as an issue with
the table, the batch (`batchId`, seq range), BitBi's numbers and the morning's metrics.

## 5. As a template for the remaining sites (phase 4)

Phase 4 is not scheduled (CR decision 3). When it is, each of the other four sites (Eden, Roshel
Rehovot, Roshel-Beer-Sheva, GesherGolan) runs this runbook with its own row in the table at the
top, plus what a move adds:

- BitBi's written go-ahead for **that** site before anything starts (CR decision 9);
- the site is created on this server, the extractor switched to it, and the first session is a full
  `FULL_SNAPSHOT`, so every row carries `_row_hash` and section 1's re-baseline is not needed (CR
  decision 8);
- `config.toml` checked for `track_duplicates` on that site's machine;
- the extractor's frequency set on the site;
- the same seven-night count, recorded in that site's own ticket.

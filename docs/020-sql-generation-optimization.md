# 020 — SQL Generation Concurrency Control & Memory Optimization

## Context

On Feb 24, 2026, the DFM dev server experienced a complete outage caused by OOM (Out of Memory).
Root cause: 4 batch operations completed simultaneously at 12:00 UTC, triggering 4 concurrent SQL
generation processes that consumed all available memory (98% of 4 GB task limit), killing the ECS tasks.

### Current Architecture

```
BatchCompletedEvent
  → BatchEventListener.onBatchCompleted()         [sync, same thread]
    → PluginEventDispatcher.dispatch()             [@Async("pluginExecutor"), 5-10 threads]
      → BitBiPlugin.execute()                      [pluginExecutionExecutor, 10-20 threads]
        → SqlGenerationService.generateSqlForBatch()
          → readCsvContentFromS3() × N files       [full file into StringBuilder]
          → CsvDiffService.compare()               [sort in-memory, Myers diff on strings]
          → SqlStatementGenerator.generate()
```

**No concurrency limits** — up to 20 SQL generations can run concurrently, each loading
full CSV files (up to 1.6 MB per file, 100 files per batch) into memory.

### Memory Profile Per File Comparison

Current implementation creates ~6× file size in memory per file:

| Step | Memory | Object |
|------|--------|--------|
| 1. Load current CSV from S3 | 1× file size | `String` (StringBuilder) |
| 2. Load previous CSV from S3 | 1× file size | `String` (StringBuilder) |
| 3. Parse + sort current CSV | 1× file size | `List<List<String>>` → write back to `String` |
| 4. Parse + sort previous CSV | 1× file size | `List<List<String>>` → write back to `String` |
| 5. Split sorted strings into lines | 2× lines count | `List<String>` × 2 (for Myers diff) |
| 6. Diff JSON + parse JSON tree | ~1× changes | `String` + `JsonNode` tree |

**Worst case**: 2 × 1.6 MB × 6 = ~19 MB per file. With 15 files × 4 concurrent batches = **~1.1 GB** just for CSV processing.

### Algorithm Inefficiencies

1. **Unnecessary serialization round-trip**: Rows parsed from CSV → sorted → serialized back to string → split into lines for diff → diff produces JSON string → JSON parsed back into objects → rows re-parsed from CSV lines
2. **Myers diff on text lines**: Treating CSV rows as opaque text strings means the diff algorithm doesn't leverage CSV structure (column-level comparison happens later, re-parsing each line)
3. **No streaming**: Full file loaded into memory; no streaming from S3

---

## Tasks

### Task 1: Add Semaphore to Limit Concurrent SQL Generations

**Priority**: HIGH (prevents OOM recurrence)
**Effort**: Small
**Risk**: Low

**What**: Add a configurable `Semaphore` to `SqlGenerationService` that limits how many
SQL generation operations can run concurrently. When the limit is reached, additional
generations wait (with timeout) instead of consuming unbounded memory.

**Where**:
- `SqlGenerationService.java` (lines 110, 134) — acquire semaphore before generation, release in finally
- `application.yml` / `application-prod.yml` — add config property
- `PluginAsyncConfiguration.java` — optionally reduce thread pool sizes

**Implementation**:
```java
// SqlGenerationService.java
@Value("${plugin.sql-generation.max-concurrent:2}")
private int maxConcurrentGenerations;

@Value("${plugin.sql-generation.semaphore-timeout-seconds:120}")
private int semaphoreTimeoutSeconds;

private Semaphore sqlGenerationSemaphore;

@PostConstruct
void initSemaphore() {
    sqlGenerationSemaphore = new Semaphore(maxConcurrentGenerations, true); // fair=true
}

public Optional<PluginSqlGeneration> generateSqlForBatch(UUID batchId, Long accountPluginId, boolean force) {
    boolean acquired = false;
    try {
        acquired = sqlGenerationSemaphore.tryAcquire(semaphoreTimeoutSeconds, TimeUnit.SECONDS);
        if (!acquired) {
            log.warn("SQL generation semaphore timeout for batch {}", batchId);
            throw new SqlGenerationException("SQL generation queue full, try again later", null);
        }
        // ... existing logic ...
    } finally {
        if (acquired) sqlGenerationSemaphore.release();
    }
}
```

**Config defaults**:
- `max-concurrent: 2` — at most 2 SQL generations run at once (each can use ~500 MB)
- `semaphore-timeout-seconds: 120` — wait up to 2 min before giving up

**Metrics to add**:
- `sql.generation.semaphore.queue.size` — Gauge: current waiters
- `sql.generation.semaphore.timeouts` — Counter: timed-out acquisitions
- `sql.generation.semaphore.acquired` — Counter: successful acquisitions

**Tests**:
- Unit test: verify semaphore limits concurrency (use CountDownLatch + threads)
- Unit test: verify timeout behavior
- Unit test: verify semaphore release on exception

---

### Task 2: Replace Text-Based Diff with Direct Row Comparison (Merge-Join)

**Priority**: HIGH (largest memory savings)
**Effort**: Medium
**Risk**: Medium (core algorithm change — needs thorough testing)

**What**: Replace the current flow (sort CSV → serialize to string → Myers text diff → parse JSON → re-parse CSV lines) with a direct sorted merge-join comparison on structured `List<List<String>>`. This eliminates the intermediate string serialization, JSON round-trip, and re-parsing.

**Current flow** (6 steps, ~6× memory):
```
CSV string → parse → List<List<String>> → sort → serialize to string
    → splitIntoLines → Myers diff → JSON string → parse JSON → parseCsvLine per change
```

**Proposed flow** (3 steps, ~2× memory):
```
CSV string → parse → List<List<String>> → sort → merge-join compare → CsvRowDiff list
```

**Where**:
- `CsvDiffService.java` — new `compareSorted()` method replaces `compare()`
- `DiffServiceImpl.java` — no longer called for CSV comparison (still used for file comparison UI)

**Algorithm**: Sorted merge-join

```java
// Both lists are already sorted by all columns
List<CsvRowDiff> compareSorted(List<List<String>> previousRows, List<List<String>> currentRows, List<String> headers) {
    List<CsvRowDiff> diffs = new ArrayList<>();
    int pi = 0, ci = 0;

    while (pi < previousRows.size() && ci < currentRows.size()) {
        List<String> prev = previousRows.get(pi);
        List<String> curr = currentRows.get(ci);
        int cmp = compareRows(prev, curr);

        if (cmp == 0) {
            // Identical rows — no change
            pi++; ci++;
        } else if (cmp < 0) {
            // prev < curr — row was deleted
            diffs.add(CsvRowDiff.deleted(pi, toMap(prev, headers)));
            pi++;
        } else {
            // prev > curr — row was added
            diffs.add(CsvRowDiff.added(ci, toMap(curr, headers)));
            ci++;
        }
    }

    // Remaining previous rows = deleted
    while (pi < previousRows.size()) {
        diffs.add(CsvRowDiff.deleted(pi, toMap(previousRows.get(pi), headers)));
        pi++;
    }
    // Remaining current rows = added
    while (ci < currentRows.size()) {
        diffs.add(CsvRowDiff.added(ci, toMap(currentRows.get(ci), headers)));
        ci++;
    }

    return diffs;
}
```

**Modification detection**: Adjacent DELETE+INSERT pairs with shared column values → UPDATE.
Post-process the diffs list (same logic as current `parseDiffJson` but on structured data, no re-parsing needed).

**Memory savings**:
- Eliminates: sorted string × 2, line list × 2, diff JSON string, Jackson tree
- Keeps: `List<List<String>>` × 2 (required for sorting)
- Net savings: ~60-70% memory reduction per file comparison

**Tests**:
- Port all existing `CsvDiffServiceTest` cases to verify identical output
- Add edge cases: empty files, single-row files, all-changed, all-unchanged
- Add large-file test (10K rows) to verify correctness

**Migration**: Keep old `DiffServiceImpl` for the file comparison UI feature. Only CSV SQL generation uses the new merge-join path.

---

### Task 3: Release CSV Strings After Parsing (Eager GC)

**Priority**: HIGH (easy win, complements Task 2)
**Effort**: Small
**Risk**: Low

**What**: In `SqlGenerationService.generateSqlContent()`, null out the raw CSV strings
after parsing into structured rows, allowing GC to reclaim memory before the next file
is processed.

**Where**:
- `SqlGenerationService.java` — `generateSqlContent()` method (line 394-458)
- `CsvDiffService.java` — refactor `compare()` to accept structured data or release strings internally

**Implementation** (if Task 2 is not yet done):
```java
// In generateSqlContent(), for each file:
String currentCsvContent = readCsvContentFromS3(currentFile.getS3Key());
List<String> headers = extractHeaders(currentCsvContent);

String previousCsvContent = "";
if (previousFile != null) {
    previousCsvContent = readCsvContentFromS3(previousFile.getS3Key());
}

List<CsvRowDiff> diffs = csvDiffService.compare(previousCsvContent, currentCsvContent, headers);

// Strings no longer needed — help GC
currentCsvContent = null;
previousCsvContent = null;
```

**If Task 2 is done first**: This becomes part of the new `compare()` method — parse CSV
strings into `List<List<String>>`, immediately release the string references, then run
merge-join on structured data.

---

### Task 4: Add Per-File Size Limit

**Priority**: MEDIUM
**Effort**: Small
**Risk**: Low

**What**: Add a configurable maximum file size for CSV processing. Skip files exceeding the
limit and log a warning. This prevents a single large file from consuming excessive memory.

**Where**:
- `SqlGenerationService.java` — check `UploadedFile.getFileSize()` before `readCsvContentFromS3()`
- `application.yml` — `plugin.sql-generation.max-file-size-bytes: 52428800` (50 MB default)

**Implementation**:
```java
@Value("${plugin.sql-generation.max-file-size-bytes:52428800}")
private long maxFileSizeBytes;

// In generateSqlContent(), before reading each file:
if (currentFile.getFileSize() > maxFileSizeBytes) {
    log.warn("Skipping CSV file exceeding size limit: file={}, size={}, limit={}",
        currentFile.getOriginalFileName(), currentFile.getFileSize(), maxFileSizeBytes);
    meterRegistry.counter("sql.generation.files.skipped.size_limit").increment();
    continue;
}
```

**Tests**:
- Unit test: file at limit → processed
- Unit test: file over limit → skipped with warning
- Unit test: verify metric incremented on skip

---

### Task 5: Add Memory-Aware Backpressure via JVM Heap Monitoring

**Priority**: MEDIUM
**Effort**: Small
**Risk**: Low

**What**: Before starting SQL generation for each file, check JVM heap usage. If heap usage
exceeds a configurable threshold (e.g., 80%), pause processing and trigger GC, or skip remaining
files with a warning.

**Where**:
- `SqlGenerationService.java` — `generateSqlContent()` loop

**Implementation**:
```java
@Value("${plugin.sql-generation.heap-threshold-percent:80}")
private int heapThresholdPercent;

private boolean isMemoryPressureHigh() {
    Runtime rt = Runtime.getRuntime();
    long used = rt.totalMemory() - rt.freeMemory();
    long max = rt.maxMemory();
    int usedPercent = (int) (used * 100 / max);
    return usedPercent >= heapThresholdPercent;
}

// In generateSqlContent() loop, before processing each file:
if (isMemoryPressureHigh()) {
    log.warn("High memory pressure ({}%), requesting GC before processing file: {}",
        heapUsedPercent, currentFile.getOriginalFileName());
    System.gc(); // Suggest GC
    if (isMemoryPressureHigh()) {
        log.error("Memory still high after GC, skipping remaining files for batch: {}",
            data.batch.getId());
        meterRegistry.counter("sql.generation.aborted.memory_pressure").increment();
        break;
    }
}
```

**As shipped** (the sketch above is the original proposal and differs): the check is a single
pre-flight in `refuseUnderMemoryPressure()` — taken once per generation, before the attempt is
announced — rather than a per-file loop with a `System.gc()` retry, the reading comes from
`MemoryMXBean` rather than `Runtime`, it is **ceiling**-rounded, and the comparison is **strict**:

```java
int heapUsagePercent = getHeapUsagePercent();          // read once: logged == what tripped it
if (isMemoryPressureHigh(heapUsagePercent)) { ... }    // heapUsagePercent > heapThresholdPercent
```

Strict is what makes the key able to say "disabled" — the reading is clamped at 100, so
`heap-threshold-percent: 100` switches the abort off, which is what all three call sites setting
100 always claimed it meant (issue #174). It also cancels the rounding: for an integer threshold
`T`, `ceil(x) > T` is exactly `x > T`, so the predicate is "usage is strictly above `T`%" with no
half-percent either way.

**The abort is a refusal, not an outcome** (issue #181). It used to return `null`, which the
callers could not tell from "this batch produced no changes":

* `DeltaSqlQueueService.processNextPending` marked the segment processed, so the batch's SQL was
  dropped and never retried;
* `doRegenerateForBatch` substituted a `-- No changes detected` artifact, persisted it, and
  `PluginHistoryService` then marked the **original** generation superseded — an abort during an
  admin regeneration replaced a good generation with an empty one, and the response reported
  success. Heap pressure is most likely precisely during an admin regeneration of a large batch,
  which is the recovery path for the queue-side drop, so an operator recovering from one dropped
  batch could destroy another.

It now throws `SqlGenerationService.MemoryPressureAbortedException`, a subclass of
`SqlGenerationException`, so it lands on the failure paths that already exist:

| Caller | Before | Now |
|---|---|---|
| `DeltaSqlQueueService.processNextPending` | segment marked processed, SQL lost | throws before the mark, so the segment stays pending and the next drain offers it again |
| `POST /api/v1/account/plugins/{pluginId}/generate-sql` (owner) and `POST /api/v1/plugins/{pluginId}/accounts/{accountId}/generate-sql` (admin) | `200` "SQL generation skipped" | `500 INTERNAL_ERROR` quoting the refusal |

(A third row used to name `SqlGenerationService.doRegenerateForBatch` — empty artifact stored,
original superseded. #190 then retired the whole regeneration path, so that consumer no longer
exists; the history stays in the bullet list above.)

(`generateSqlForBatchAsync` — feature 015's async reinit generator, an `@Async("pluginExecutor")`
method whose Javadoc described a reinit flow that had been removed — never appears in the table:
it had no callers, and #185 (folding #210) deleted it. `PluginHistoryService`'s "SQL generation no
longer triggered for reinit" comment is the remaining record of that flow.)

The regeneration hazard was closed for good by #190 itself: rather than moving the generation out
of the caller's transaction, #190 retired the regeneration path entirely (it could not serve any
segment-backed batch, and the V11 unique on `source_batch_id` forbade the second row it needed).
Recovery is delete + manual generate-SQL, or a reinit for a batch the client already fetched.

`null` keeps its one remaining meaning — the diff really was empty — and the batch that was refused
is now named durably: the existing `catch (RuntimeException)` writes a `SQL_GENERATION_FAILED`
entry, visible to the account on
`GET /api/v1/account/plugins/{pluginId}/logs`. The refusal is **kept off** `sql.generation.errors`
and logged as a single **WARN** at the point it is raised (carrying the
reading and the threshold), not as an ERROR: those series and an ERROR-rate alert both mean
"generation is broken", and this condition repairs itself when the heap does — the rule #162
applied to `delta.checkpoint.builds.aborted`. It is also refused *before* the
`SQL_GENERATION_STARTED` entry, so an attempt costs one audit row rather than a pair announcing a
generation that never started. Two neighbouring series do move without meaning anything is wrong:
`sql.generation.semaphore.acquired` counts the refused attempt (the permit is taken first), while
`sql.generation.duration` gets no sample from it.

`sql.generation.aborted.memory_pressure` keeps its name and now means something slightly
different: it counts **refusals, not batches lost**. Before, a refused batch was refused once,
because the segment was consumed; now the batch is retried, so one batch under a long pressure
episode increments it repeatedly. Read it as a rate ("this pod is refusing work") and the audit
entries for *which* batches — a dashboard reading it as a count of affected batches will
overstate.

**Retrying is safe here, and that is a property of this abort rather than a general rule.** The
check is a pre-flight on the *pod's* heap, not on the batch, so the same batch generates normally
on a later attempt; nothing about it is deterministically too large. The deterministic Parquet
size ceilings are the opposite case — they repeat identically for ever — and are bounded by
attempt counters (#149) or settled as `ABANDONED` (036) instead.

**What sets the retry rate is the wake, not the sweep.** A throw ends the whole
`DeltaSqlSweepWorker` drain, so there is exactly one refused attempt per wake — but the pool is
woken by `BitBiPlugin.execute` on **every** `BATCH_COMPLETED` and by a plugin reinit, not only by
the `plugin.sql-generation.delta-sweep-ms` tick (60 s). On a busy fleet a pressure episode
therefore produces one refused attempt, one WARN line and one audit row per completed batch,
which is the intended visibility but is repetitive; the floor is the sweep tick, not the ceiling.

**One caveat on "unbounded retry is safe": it assumes the threshold is configured sanely.**
Since **#185** the whole `plugin.sql-generation.*` block is validated — each key in its consuming
bean's constructor — and an out-of-range value **fails the application context at startup**:

| Key | Range | Validated in |
|---|---|---|
| `heap-threshold-percent` | `1..100` | `SqlGenerationService` |
| `max-concurrent` | `>= 1` | `SqlGenerationService` |
| `semaphore-timeout-seconds` | `>= 1` | `SqlGenerationService` |
| `delta-max-concurrent` | `>= 1` | `DeltaSqlSweepWorker` |
| `delta-sweep-ms` | `>= 1` | `DeltaSqlSweepWorker` |

None of the five was scoped out. 100 stays the documented off-switch of #174 (the strict
comparison and the clamp are untouched), and the heap floor is **1, not 0**: the ceiling-rounded
reading of a live JVM is never 0, so a threshold of 0 refuses every generation exactly like a
negative value — and this deployment's "0 disables" convention elsewhere
(`delta.parquet.max-scratch-bytes`, `delta.checkpoint.max-fold-bytes`) would have made 0 read as a
second off-switch. **Fail fast rather than a startup WARN is an owner decision recorded on the
ticket**, for three reasons: the deployment is a GKE rolling update, so a pod that refuses to
start does not take the service down — old replicas keep serving and the rollout goes red
immediately, which is exactly the visibility a config typo needs; a WARN is the channel already
proven unread — the "memory-pressure abort disabled" startup line exists since #174 and would
have stopped nobody; and the failure costs are asymmetric — `800` silently disables the heap
guard, a negative value turns the whole deployment into an endless retry loop that per #212 ends
in silent data loss once retention passes over the pending segments, `delta-max-concurrent: 0`
used to crash-loop through `ArrayBlockingQueue`'s message-less `IllegalArgumentException` (naming
neither key nor value), and `delta-sweep-ms: 0` was accepted by Spring and busy-looped the
fallback sweep on a green rollout. A failed rollout that names the key is strictly cheaper than
any of them. **Two limits of the promise are stated rather than implied.** The refusal names the
key and the value for a *well-formed integer* out of range; a value Spring cannot convert at all
(an env var present but empty — `${VAR:80}` does not default for `""` — or `"80%"`) fails
earlier, during `@Value` conversion, with a message naming the constructor parameter rather than
the key. And validation does not bound a value that is in range and still wrong for the pod — a
threshold of `8` is legal and makes the check true for nearly every generation, stalling the
delta-SQL queue (segments accumulate with `plugin_sql_at` unset and `/sql-changes` goes quiet) —
so the paragraph below about the retry horizon still applies to any persistently refusing
configuration. One grammar narrowing is deliberate: reading `delta-sweep-ms` into a `long` for
validation rejects the duration-string form (`PT5M`) that a bare `fixedDelayString` used to
accept — such a value now dies in `@Value` conversion at startup (no deployment, profile or test
ever used one). The Boot-native alternative was weighed and not taken:
`@ConfigurationProperties` + `@Validated` with jakarta constraints (the `ParquetExportProperties`
shape) would name key, value *and* property origin, and would catch malformed values too — the
per-consumer-constructor check is the recorded owner decision for this block, five keys across
two beans did not justify a properties class per consumer, and the two limits above are the
accepted cost.

**And "recoverable" has a horizon**: `ChangelogRetentionService.prune` deletes below-checkpoint
segments past `delta.retention.audit-window-segments` (20) without regard for `plugin_sql_at IS
NULL`, so a segment left pending long enough is eventually deleted with its S3 object and the
batch's SQL is lost after all — silently, and without the audit row that marks a refusal. That is
not introduced here (it applies to any generation that keeps failing, which is what "stays pending
for the sweep" has always meant) but it bounds the retry window, and it is **#212**.

**Tests**:
- Unit test: stub `getHeapUsagePercent()` above/at/below the threshold → verify the boundary
- Unit test: verify metric incremented
- `DeltaSqlQueueMemoryPressureTest` — the real service behind the delta-SQL queue: a refused
  attempt leaves the segment pending, names the batch in the audit log, and does not move
  `sql.generation.errors`; the same wiring below the threshold consumes the segment as usual

---

### Task 6: Reduce Thread Pool Sizes for SQL Generation

**Priority**: MEDIUM (quick config change)
**Effort**: Tiny
**Risk**: Low

**What**: Reduce `pluginExecutionExecutor` thread pool sizes. Currently 10-20 threads can
all run SQL generation simultaneously, but with the semaphore (Task 1) capping at 2, the
extra threads just consume stack memory for nothing.

**Where**:
- `PluginAsyncConfiguration.java` — reduce `pluginExecutionExecutor`

**Changes**:
```java
// pluginExecutionExecutor — for actual plugin.execute() calls
executor.setCorePoolSize(4);   // was 10
executor.setMaxPoolSize(8);    // was 20
executor.setQueueCapacity(50); // was 100
```

**Rationale**: With semaphore limiting to 2 concurrent SQL generations, 4 core threads
are plenty (2 for SQL generation + 2 spare for other plugin operations).

---

### Task 7: Add JVM Memory Limits to ECS Task Definition

**Priority**: HIGH (infrastructure)
**Effort**: Small
**Risk**: Low

**What**: Set explicit `-Xmx` JVM flag to prevent Java from consuming all container memory.
Currently Java has no heap limit and shares the 4 GB task memory with the frontend container.

**Where**:
- ECS task definition / Dockerfile / entrypoint script
- `JAVA_OPTS` or `JDK_JAVA_OPTIONS` environment variable

**Recommended settings**:
```
JDK_JAVA_OPTIONS=-Xmx2560m -Xms512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200
```

**Rationale**:
- 4 GB task total, ~512 MB for frontend (nginx + React static), ~1 GB OS/overhead
- 2560 MB max heap gives Java a hard limit with room for OS, native memory, etc.
- G1GC is better for heap sizes >1 GB (default for Java 21)

---

### Task 8: Streaming CSV Parsing from S3 (Future Optimization)

**Priority**: LOW (largest effort, best combined with Task 2)
**Effort**: Large
**Risk**: Medium

**What**: Instead of loading full CSV content into a `String`, stream-parse CSV records
directly from the S3 `ResponseInputStream`. This eliminates the initial full-file string
allocation.

**Where**:
- `SqlGenerationService.readCsvContentFromS3()` → replace with `streamCsvRowsFromS3()`
- `CsvDiffService` → accept `List<List<String>>` instead of `String`

**Proposed flow**:
```java
List<List<String>> streamCsvRowsFromS3(String s3Key, List<String> headers) {
    // Stream from S3 → GZIPInputStream → InputStreamReader → CSVParser
    // Build List<List<String>> directly, never holding full file as String
    try (ResponseInputStream<GetObjectResponse> s3Response = s3Client.getObject(request)) {
        InputStream is = s3Key.endsWith(".gz") ? new GZIPInputStream(s3Response) : s3Response;
        try (CSVParser parser = CSVFormat.DEFAULT.withFirstRecordAsHeader()
                .parse(new InputStreamReader(is, UTF_8))) {
            List<List<String>> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                List<String> row = new ArrayList<>(headers.size());
                for (String h : headers) {
                    row.add(record.isMapped(h) ? record.get(h) : "");
                }
                rows.add(row);
            }
            return rows;
        }
    }
}
```

**Memory savings**: Eliminates the full `String` representation of CSV (~1× file size per file).
Combined with Task 2 (merge-join), total memory per file = ~2× row data (sorted previous + current).

**Dependency**: Best implemented together with Task 2 (merge-join algorithm).

---

## Implementation Status

| Task | Status | Tests |
|------|--------|-------|
| Task 1: Semaphore | ✅ Done | 8 tests (SqlGenerationConcurrencyTest) |
| Task 2: Merge-join | ✅ Done | 22 tests (CsvDiffServiceMergeJoinTest) + 11 updated (CsvDiffServiceTest) |
| Task 3: Eager GC | ✅ Done (folded into Task 8) | Verified via SqlGenerationStreamingTest |
| Task 4: Per-file size limit | ⏭ Skipped (by request) | — |
| Task 5: Memory backpressure | ✅ Done | 3 tests (SqlGenerationStreamingTest.MemoryBackpressure) |
| Task 6: Thread pool | ✅ Done | 4 tests (PluginAsyncConfigurationTest) |
| Task 7: JVM -Xmx | ✅ Documented | Infrastructure change (see below) |
| Task 8: Streaming S3 | ✅ Done | 7 tests (SqlGenerationStreamingTest.StreamingS3Parsing) |

## Task 7: JVM Memory Configuration (Infrastructure)

### Recommended ECS Task Definition Changes

Add the following environment variable to the backend container in the ECS task definition:

```
JDK_JAVA_OPTIONS=-Xmx2560m -Xms512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200
```

### Rationale

- **4 GB task total**: ECS task has 4096 MB memory, shared between frontend (nginx) and backend (JVM)
- **Frontend**: ~512 MB for nginx + React static files + OS overhead
- **Backend**: 2560 MB max heap gives Java a hard ceiling while leaving room for native memory, thread stacks, and OS
- **G1GC**: Default for Java 21, optimal for heap sizes >1 GB with predictable pause times
- **MaxGCPauseMillis=200**: Targets 200ms GC pauses, reducing latency impact during SQL generation

### Configuration Properties

```yaml
# application.yml (or application-prod.yml)
plugin:
  sql-generation:
    max-concurrent: 2           # Max concurrent SQL generations (semaphore permits); >= 1
    semaphore-timeout-seconds: 120  # Wait up to 2 min before timing out; >= 1
    heap-threshold-percent: 80  # Abort generation when heap usage is strictly above this %;
                                # 1..100, and 100 disables the check (usage can never exceed 100%)
```

An out-of-range value in any `plugin.sql-generation.*` key **fails startup** (issue #185): the
three above are validated in the `SqlGenerationService` constructor, and the block's other two —
`delta-max-concurrent` and `delta-sweep-ms` (026) — in `DeltaSqlSweepWorker`. See "One caveat on
'unbounded retry is safe'" above for the ranges and the fail-fast reasoning.

---

## Memory Profile After Optimization

| Step | Before (per file) | After (per file) |
|------|-------------------|-------------------|
| Load CSV from S3 | 1× file size (String) | 0× (streaming to rows) |
| Parse CSV rows | 1× file size (List) | 1× file size (List) |
| Sort rows | 1× (serialized back) | In-place sort |
| Diff algorithm | 2× lines + JSON + parse | O(n) merge-join |
| **Total** | **~6× file size** | **~1× file size** (rows only) |

With semaphore (max 2 concurrent) + streaming + merge-join:
- **Before**: 2 × 1.6 MB × 6 × 15 files × 4 concurrent = ~1.1 GB
- **After**: 2 × 1.6 MB × 1 × 15 files × 2 concurrent = ~96 MB

## Key Files

| File | Path |
|------|------|
| SqlGenerationService | `src/main/java/com/bitbi/dfm/plugin/application/SqlGenerationService.java` |
| CsvDiffService | `src/main/java/com/bitbi/dfm/plugin/application/CsvDiffService.java` |
| DiffServiceImpl | `src/main/java/com/bitbi/dfm/comparison/infrastructure/DiffServiceImpl.java` |
| PluginAsyncConfiguration | `src/main/java/com/bitbi/dfm/plugin/infrastructure/PluginAsyncConfiguration.java` |
| BitBiPlugin | `src/main/java/com/bitbi/dfm/plugin/application/BitBiPlugin.java` |
| PluginEventDispatcher | `src/main/java/com/bitbi/dfm/plugin/application/PluginEventDispatcher.java` |

## Test Files

| File | Tests | Covers |
|------|-------|--------|
| `SqlGenerationConcurrencyTest` | 8 | Task 1 (Semaphore) |
| `CsvDiffServiceMergeJoinTest` | 31 | Task 2 (Merge-join) + Pre-parsed rows overload |
| `CsvDiffServiceTest` | 11 | Existing row comparison (updated) |
| `SqlGenerationStreamingTest` | 10 | Tasks 3, 5, 8 (Streaming, Memory, GC) |
| `PluginAsyncConfigurationTest` | 4 | Task 6 (Thread pool) |
| `SqlGenerationServiceTest` | 5 | BOM stripping + per-file error handling |

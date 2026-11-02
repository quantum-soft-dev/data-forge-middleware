package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.QueueRetryBackoff;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.shared.lifecycle.ApplicationShutdownSignal;
import com.bitbi.dfm.site.domain.TableSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Materializes one committed changelog segment as per-table delta Parquet files
 * (Delta Client v2 — 022, Task 8).
 *
 * <p>Streams the segment's records back from object storage and writes one typed delta file per
 * table with a declared schema to {@code egress/{siteId}/{table}/delta/seq={first}-{last}.parquet}.
 * Tables without a declared schema are skipped (logged) — the segment is still marked egressed so
 * the queue drains. Re-running after a partial failure is idempotent: the same keys are
 * overwritten.</p>
 *
 * <p><b>Nothing here scales with the segment</b> (issue #404). A DELTA session is one segment
 * however large it grows, and this class used to read it into a list, copy it per table and render
 * each table's file into a {@code byte[]}: a 289 MB segment did not fit a 2.25 GiB heap, and the
 * {@link OutOfMemoryError} killed every replica that claimed it in turn. The records now stream
 * through {@link DeltaParquetWriter#writeBatchDeltaParquet} — the completed-batch writer, one
 * scratch file per table from one shared pass — and each file is uploaded from disk. Heap is the
 * open writers' row groups, and at most {@code delta.egress.table-writers} are open at once (review
 * round 2): {@code W x delta.parquet.row-group-bytes} per worker, whatever the segment's size or its
 * table count; a segment with more tables is read once per group. The files are charged to the
 * shared scratch directory ({@link ParquetScratchBudget#EGRESS_SEGMENT}) and share the
 * completed-batch directory and per-file ceiling, since a segment's slice of a table is never larger than its batch's.</p>
 *
 * <p>This class opens <em>no</em> transaction of its own (issue #164). The pending-row claim
 * and the {@code egress_at} write are the repository's short transactions; the S3 download,
 * Parquet render and per-table uploads run with nothing open. The mark is a targeted
 * {@code UPDATE ... SET egress_at} of that column only (issue #245), not a save of the entity
 * captured at claim — a merge would un-stamp {@code plugin_sql_at} if the SQL worker had
 * finished in between. A crash after a successful upload leaves the segment pending and the
 * sweep retries — the same keys are overwritten. Calling {@link #egressSegment} with a
 * transaction already open is refused rather than silently pinning that connection across
 * the network round trip.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class DeltaEgressService {

    private static final Logger log = LoggerFactory.getLogger(DeltaEgressService.class);

    private final ChangelogSegmentRepository segmentRepository;
    private final ChangelogSegmentService changelogSegmentService;
    private final SiteSchemaService siteSchemaService;
    private final S3CheckpointStorage storage;
    private final DeltaMetrics metrics;
    private final DeltaParquetProperties parquetProperties;
    private final QueueRetryBackoff backoff;
    private final ApplicationShutdownSignal shutdownSignal;
    private final ParquetScratchBudget scratchBudget;
    private final Path tempDirectory;
    private final long maxTempBytes;
    private final int tableWriters;

    public DeltaEgressService(ChangelogSegmentRepository segmentRepository,
                              ChangelogSegmentService changelogSegmentService,
                              SiteSchemaService siteSchemaService,
                              S3CheckpointStorage storage,
                              DeltaMetrics metrics,
                              DeltaParquetProperties parquetProperties,
                              ParquetScratchBudget scratchBudget,
                              @Value("${delta.batch-parquet.temp-dir:${java.io.tmpdir}}") String tempDirectory,
                              @Value("${delta.batch-parquet.max-temp-bytes:10737418240}") long maxTempBytes,
                              @Value("${delta.egress.table-writers:8}") int tableWriters,
                              @Value("${delta.egress.retry-delay-seconds:60}") int retryDelaySeconds,
                              @Value("${delta.egress.poison-after-attempts:7}") int poisonAfterAttempts,
                              @Value("${delta.egress.claim-lease-seconds:600}") int claimLeaseSeconds,
                              ApplicationShutdownSignal shutdownSignal) {
        this.segmentRepository = segmentRepository;
        this.changelogSegmentService = changelogSegmentService;
        this.siteSchemaService = siteSchemaService;
        this.storage = storage;
        this.metrics = metrics;
        this.parquetProperties = parquetProperties;
        this.scratchBudget = scratchBudget;
        this.tempDirectory = Path.of(tempDirectory);
        this.maxTempBytes = maxTempBytes;
        if (tableWriters < 1) {
            throw new IllegalArgumentException(
                    "delta.egress.table-writers must be at least 1, got " + tableWriters);
        }
        this.tableWriters = tableWriters;
        this.backoff = new QueueRetryBackoff("delta.egress.retry-delay-seconds", retryDelaySeconds,
                "delta.egress.poison-after-attempts", poisonAfterAttempts,
                "delta.egress.claim-lease-seconds", claimLeaseSeconds);
        this.shutdownSignal = shutdownSignal;
    }

    /**
     * Claim and materialize the next pending segment (per-site head).
     *
     * <p>The read, the claim and the later {@code egress_at} write are each their own short
     * repository transaction. The claim spends the attempt and takes a lease before any work
     * (issue #404): another worker or replica is not offered the segment while the lease lives, and a
     * process killed mid-work leaves it in backoff rather than first in line for the next process.
     * The lease outlives the work, so a crash after a successful upload costs that segment the lease
     * before the retry overwrites the same keys.</p>
     *
     * @return {@code true} when a segment was materialized, or another worker claimed the head
     *         first, and the drain should continue;
     *         {@code false} when there was nothing claimable, when this attempt failed and the
     *         segment was deferred (issue #243 — the next wake claims another site's head), or when
     *         the failure was the pod shutting down. {@code false} therefore means "stop draining",
     *         not "the queue is empty".
     */
    public boolean egressNextPending() {
        refuseInsideTransaction();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<ChangelogSegment> next = segmentRepository.findNextPendingEgress(1, now);
        if (next.isEmpty()) {
            return false;
        }
        ChangelogSegment segment = next.get(0);
        int claimedAttempts = segment.getEgressAttempts() + 1;
        if (segmentRepository.leaseEgress(segment.getId(), backoff.leaseUntil(now, claimedAttempts),
                segment.getEgressAttempts()) == 0) {
            // Another worker read the same head and claimed it first. Its lease now keeps the row
            // out of the read, so the next iteration is offered a different head.
            return true;
        }
        if (backoff.isPoisoned(segment.getEgressAttempts())) {
            // Review round 1 of #404: a segment that killed its process leaves no failure line and
            // moves no counter, so this claim is the one place that can name it. Its earlier
            // attempts may also have thrown; the census is delta.egress.segments.poisoned.pending.
            log.warn("Delta egress claims segment {} of site {} (seq {}..{}) for attempt {}: its earlier "
                            + "attempts did not settle it — they failed, or the process died mid-work "
                            + "(out of heap, a liveness kill)", segment.getId(), segment.getSiteId(),
                    segment.getFirstSeq(), segment.getLastSeq(), claimedAttempts);
        }
        // Only an exception is caught. An Error — the OutOfMemoryError of issue #404 — ends the
        // process or at least this thread, and needs nothing from here: the attempt is already
        // spent and the lease keeps the segment away until it lapses.
        try {
            egressSegment(segment);
        } catch (RuntimeException e) {
            defer(segment, claimedAttempts, e);
            // Stop this drain after one deferral (issue #243, review round 1). Continuing would
            // walk the whole backlog during a systemic failure — an S3 outage, the database
            // refusing connections — spending an attempt and a cooldown on every pending segment
            // of every site, which both drowns the poisoned signal and delays recovery by the
            // accumulated cooldowns. One deferral per wake is enough to unblock the queue: the
            // deferred segment is in its cooldown, so the next wake claims a different site's
            // head and drains it to the end.
            return false;
        }
        return true;
    }

    /**
     * The #164 guard, checked before the queue claims anything (issue #243, review round 1).
     *
     * <p>{@link #egressSegment} refuses the same way, but from inside the try that turns a failure
     * into this segment's deferral — so a caller that wrapped the drain in a transaction would read
     * as "every segment in the queue is poison data" instead of as the wiring mistake it is.</p>
     */
    private void refuseInsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Refusing to drain the delta egress queue inside an active transaction: the "
                            + "S3 download and per-table uploads would hold that transaction's "
                            + "connection for the length of a network call (issue #164).");
        }
    }

    /**
     * Hold a segment that failed out of the queue for a while, instead of ending the drain on it
     * (issue #243).
     *
     * <p>Before this the exception left {@code DeltaEgressWorker.drain}, and since the claim is the
     * globally oldest per-site head with {@code LIMIT 1}, the same segment was offered first on
     * every wake — one unreadable object stopped every other site's delta Parquet. The segment is
     * still the durable queue entry and nothing is discarded: it comes back after a delay that
     * doubles per attempt, and the attempt count only escalates how loudly it is reported.</p>
     *
     * <p>The attempt was counted by the claim (issue #404), so this only moves the retry time from
     * the lease to the backoff delay.</p>
     */
    private void defer(ChangelogSegment segment, int attempts, RuntimeException failure) {
        if (shutdownSignal.isShuttingDown()) {
            // The pod is closing, so the S3 client or the data source may already be gone: this is
            // the process ending, not the segment failing, and #162's rule is that such an ending
            // records no verdict. The claim already spent the attempt (#404), so it is handed back
            // and the next process claims the segment at once.
            log.info("Delta egress for segment {} ended with the shutdown; it stays pending and "
                    + "spends no attempt", segment.getId());
            try {
                segmentRepository.releaseEgress(segment.getId(), attempts);
            } catch (RuntimeException e) {
                // The data source may be gone too; the lease then lapses on its own.
                log.debug("Could not hand back the claim on segment {} during shutdown", segment.getId(), e);
            }
            return;
        }
        LocalDateTime retryAt = backoff.nextRetryAt(LocalDateTime.now(ZoneOffset.UTC), attempts);
        int deferred;
        try {
            deferred = segmentRepository.deferEgress(segment.getId(), retryAt, attempts);
        } catch (RuntimeException e) {
            // The deferral write is the likeliest thing to fail when the segment's own failure was
            // the database — and this class is the only place that ever logs a top-level egress
            // failure, so letting the second exception replace the first would lose the incident
            // entirely (review round 3).
            e.addSuppressed(failure);
            throw e;
        }
        if (deferred == 0) {
            // Claim-scoped predicate refused: the work landed on another replica while this attempt
            // was failing, the row is gone, or its attempt count moved (a peer's deferral, a
            // reset). Reporting it would send an operator after a segment that is already done
            // (review round 1) — but the failure itself is still worth one line, since nothing else
            // logs it (round 3).
            log.debug("Not deferring segment {}: it is no longer this claim's to defer", segment.getId(),
                    failure);
            return;
        }
        metrics.egressFailed();
        if (backoff.isPoisoned(attempts)) {
            metrics.egressSegmentPoisoned();
            log.error("Delta egress failed {} times for segment {} of site {} (seq {}..{}, key {}) — "
                            + "the segment stays queued and is retried at {}, and its site's later "
                            + "segments wait behind it. Nothing discards it: fix the cause (declared "
                            + "schema, object, ceilings) or delete the batch. If this fires for many "
                            + "segments at once the cause is systemic, not the data; "
                            + "delta.egress.poison-after-attempts sets the threshold",
                    attempts, segment.getId(), segment.getSiteId(), segment.getFirstSeq(),
                    segment.getLastSeq(), segment.getS3Key(), retryAt, failure);
        } else {
            log.warn("Delta egress failed for segment {} of site {} (attempt {}, retry at {}): {}",
                    segment.getId(), segment.getSiteId(), attempts, retryAt, failure.toString());
        }
    }

    /**
     * Write the segment's delta Parquet files and mark it egressed.
     *
     * @param segment the committed changelog segment to materialize
     * @throws IllegalStateException when called with a transaction already open
     */
    public void egressSegment(ChangelogSegment segment) {
        metrics.timeEgress(() -> egressSegmentTimed(segment));
    }

    private void egressSegmentTimed(ChangelogSegment segment) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Refusing to egress a changelog segment inside an active transaction: the "
                            + "S3 download and per-table uploads would hold that transaction's "
                            + "connection for the length of a network call (issue #164).");
        }
        Map<String, TableSchema> schemas = siteSchemaService.getTableSchemas(segment.getSiteId());
        Set<String> tables = tablesOf(segment);
        List<String> renderable = new ArrayList<>();
        for (String table : tables) {
            if (schemas.containsKey(table)) {
                renderable.add(table);
            } else {
                log.warn("No declared schema for table {} of site {} — skipping delta Parquet egress",
                        table, segment.getSiteId());
            }
        }

        // At most delta.egress.table-writers tables at once (review round 2 of #404). A writer per
        // table would bound the heap by the table count instead of the segment's size — 87 tables x
        // a 16 MiB row group x two workers is more than the pod has — so the tables are written in
        // groups, one pass over the segment per group, the #292 trade: ceil(tables / W) reads of the
        // segment for a heap of W row groups per worker. Each group's files are uploaded and deleted
        // before the next opens, which bounds the scratch directory the same way.
        PhaseClock clock = new PhaseClock();
        try {
            for (int from = 0; from < renderable.size(); from += tableWriters) {
                egressGroup(segment, renderable.subList(from, Math.min(from + tableWriters, renderable.size())),
                        schemas, clock);
            }
        } finally {
            if (clock.hasSamples()) {
                // The phases this meter has always had: reading the segment (transfer and parse) and
                // writing the files (the decimal envelope pass included), summed over the groups.
                metrics.recordEgressPhase("download", clock.downloadNanos() + clock.decodeNanos());
                if (clock.writeAttempted() || clock.decimalScanAttempted()) {
                    metrics.recordEgressPhase("write", clock.writeNanos() + clock.decimalScanNanos());
                }
            }
        }

        // Targeted UPDATE of this marker only (issue #245): a save of the claim-time snapshot
        // would merge plugin_sql_at back to NULL after the SQL worker had already stamped it.
        segmentRepository.markEgressed(segment.getId());
        metrics.segmentEgressed();

        log.info("Delta egress done: siteId={}, seq={}..{}, tables={}",
                segment.getSiteId(), segment.getFirstSeq(), segment.getLastSeq(), tables);
    }

    /**
     * Write one group of tables from one pass over the segment and upload them, holding at most the
     * group's scratch files at once.
     */
    private void egressGroup(ChangelogSegment segment, List<String> group, Map<String, TableSchema> schemas,
                             PhaseClock clock) {
        Map<String, Path> files = new LinkedHashMap<>();
        // One lease per scratch file, released with the file in the finally below — the group's files
        // are kept until its last upload, so the directory carries them all at once (#150).
        Map<String, ScratchLease> leases = new LinkedHashMap<>();
        Map<String, DeltaParquetWriter.TableWriteRequest> requests = new LinkedHashMap<>();
        try {
            Files.createDirectories(tempDirectory);
            for (String table : group) {
                Path output = Files.createTempFile(tempDirectory,
                        ParquetScratch.EGRESS_PREFIX + segment.getId() + "-", ".parquet");
                files.put(table, output);
                ScratchLease lease = scratchBudget.open(ParquetScratchBudget.EGRESS_SEGMENT);
                leases.put(table, lease);
                requests.put(table, new DeltaParquetWriter.TableWriteRequest(output, schemas.get(table), lease));
            }

            DeltaParquetWriter.BatchWriteResult written = DeltaParquetWriter.writeBatchDeltaParquet(requests,
                    consumer -> changelogSegmentService.forEachRecord(segment.getS3Key(), consumer, clock),
                    maxTempBytes, parquetProperties.rowGroupBytes(), clock);

            written.failures().forEach((table, failure) -> {
                if (failure.storage()) {
                    // The directory's condition, not the table's: skipping would mark the segment
                    // egressed without this file for good. The segment fails and is deferred.
                    throw new IllegalStateException("Delta Parquet for table " + table + " of site "
                            + segment.getSiteId() + " could not be written to scratch: " + failure.error());
                }
                // One poison table (data the declared schema cannot render, or a file past its
                // ceiling) must not wedge the queue: without this the whole segment stays pending
                // and the sweep retries it forever, blocking every other table — the
                // skip-and-continue contract CheckpointService documents. Render only: an upload
                // failure is transient and must keep the segment pending for the sweep, so it stays
                // outside this branch.
                log.error("Delta Parquet render failed for table {} of site {} (seq {}..{}) — skipping "
                                + "the table's delta file (check the declared schema against the data): {}",
                        table, segment.getSiteId(), segment.getFirstSeq(), segment.getLastSeq(),
                        failure.error());
            });

            written.files().forEach((table, result) -> {
                metrics.timeEgressPhase("upload", () -> storage.uploadDelta(
                        segment.getSiteId(), table, segment.getFirstSeq(), segment.getLastSeq(),
                        files.get(table)));
                // After the upload, not before it (issue #215, review round 2): an upload failure on
                // a later table propagates, the segment is never marked, and the sweep re-renders
                // every table -- re-counting the ones that already uploaded. The ordering removes the
                // re-count for the failing table only (review round 3).
                metrics.unrepresentableDecimalsDegraded(result.degradedDecimals());
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create a delta Parquet scratch file", e);
        } finally {
            for (Path file : files.values()) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    log.warn("Could not delete delta Parquet scratch file {}", file, e);
                }
            }
            // After the deletes, and even when one failed — BatchParquetFinalizationService's reasoning:
            // a lease held for an undeleted file would shrink the directory for the life of the pod,
            // while the file itself is residue ParquetScratchOrphanSweeper reclaims.
            leases.values().forEach(ScratchLease::close);
        }
    }

    /**
     * The tables a segment touches, in the order they first appear.
     *
     * <p>From the segment's stats when it has them, so the records are read once. A segment written
     * before stats existed names nothing, and one extra streamed pass collects the names — still a
     * stream: the point of #404 is that no list of records is ever built.</p>
     */
    private Set<String> tablesOf(ChangelogSegment segment) {
        if (segment.getStats() != null) {
            return new LinkedHashSet<>(segment.getStats().keySet());
        }
        Set<String> tables = new LinkedHashSet<>();
        changelogSegmentService.forEachRecord(segment.getS3Key(), record -> tables.add(record.getTable()), null);
        return tables;
    }
}

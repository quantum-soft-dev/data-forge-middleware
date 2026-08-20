package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.QueueRetryBackoff;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.shared.lifecycle.ApplicationShutdownSignal;
import com.bitbi.dfm.site.domain.TableSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Materializes one committed changelog segment as per-table delta Parquet files
 * (Delta Client v2 — 022, Task 8).
 *
 * <p>Reads the segment's records back from object storage, splits them by table, and writes one
 * typed delta file per table with a declared schema to
 * {@code egress/{siteId}/{table}/delta/seq={first}-{last}.parquet}. Tables without a declared
 * schema are skipped (logged) — the segment is still marked egressed so the queue drains.
 * Re-running after a partial failure is idempotent: the same keys are overwritten.</p>
 *
 * <p>This class opens <em>no</em> transaction of its own (issue #164). The pending-row claim
 * and the {@code egress_at} write are the repository's short transactions; the S3 download,
 * Parquet render and per-table uploads run with nothing open. A crash after a successful
 * upload leaves the segment pending and the sweep retries — the same keys are overwritten.
 * Calling {@link #egressSegment} with a transaction already open is refused rather than
 * silently pinning that connection across the network round trip.</p>
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

    public DeltaEgressService(ChangelogSegmentRepository segmentRepository,
                              ChangelogSegmentService changelogSegmentService,
                              SiteSchemaService siteSchemaService,
                              S3CheckpointStorage storage,
                              DeltaMetrics metrics,
                              DeltaParquetProperties parquetProperties,
                              @Value("${delta.egress.retry-delay-seconds:60}") int retryDelaySeconds,
                              @Value("${delta.egress.poison-after-attempts:7}") int poisonAfterAttempts,
                              ApplicationShutdownSignal shutdownSignal) {
        this.segmentRepository = segmentRepository;
        this.changelogSegmentService = changelogSegmentService;
        this.siteSchemaService = siteSchemaService;
        this.storage = storage;
        this.metrics = metrics;
        this.parquetProperties = parquetProperties;
        this.backoff = new QueueRetryBackoff("delta.egress.retry-delay-seconds", retryDelaySeconds,
                "delta.egress.poison-after-attempts", poisonAfterAttempts);
        this.shutdownSignal = shutdownSignal;
    }

    /**
     * Claim and materialize the next pending segment (per-site head, {@code SKIP LOCKED}).
     *
     * <p>The claim query and the later {@code egress_at} write are each their own short
     * repository transaction. A crash between them leaves the segment pending; the sweep
     * retries and overwrites the same keys.</p>
     *
     * @return {@code true} if a segment was processed, {@code false} when the queue is empty
     */
    public boolean egressNextPending() {
        refuseInsideTransaction();
        List<ChangelogSegment> next = segmentRepository.findNextPendingEgress(
                1, LocalDateTime.now(ZoneOffset.UTC));
        if (next.isEmpty()) {
            return false;
        }
        ChangelogSegment segment = next.get(0);
        try {
            egressSegment(segment);
        } catch (RuntimeException e) {
            defer(segment, e);
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
     * <p>The attempt count in the log is this claim's snapshot plus one, while the stored count is
     * incremented in the database — two replicas attempting the same segment at once each add one,
     * so the row can be ahead of the line logged here.</p>
     */
    private void defer(ChangelogSegment segment, RuntimeException failure) {
        if (shutdownSignal.isShuttingDown()) {
            // The pod is closing, so the S3 client or the data source may already be gone: this is
            // the process ending, not the segment failing, and #162's rule is that such an ending
            // records no verdict. The segment stays pending and the next process claims it.
            log.info("Delta egress for segment {} ended with the shutdown; it stays pending and "
                    + "spends no attempt", segment.getId());
            return;
        }
        int attempts = segment.getEgressAttempts() + 1;
        LocalDateTime retryAt = backoff.nextRetryAt(LocalDateTime.now(ZoneOffset.UTC), attempts);
        if (segmentRepository.deferEgress(segment.getId(), retryAt) == 0) {
            // The marker predicate refused: the work landed on another replica while this attempt
            // was failing, or the row is gone. Reporting it would send an operator after a segment
            // that is already done (review round 1).
            log.debug("Not deferring segment {}: it is no longer pending egress", segment.getId());
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
        List<ChangeRecord> records = metrics.timeEgressPhase("download",
                () -> changelogSegmentService.readRecords(segment.getS3Key()));

        Map<String, List<ChangeRecord>> byTable = new LinkedHashMap<>();
        for (ChangeRecord record : records) {
            byTable.computeIfAbsent(record.getTable(), table -> new ArrayList<>()).add(record);
        }

        Map<String, TableSchema> schemas = siteSchemaService.getTableSchemas(segment.getSiteId());
        byTable.forEach((table, tableRecords) -> {
            TableSchema schema = schemas.get(table);
            if (schema == null) {
                log.warn("No declared schema for table {} of site {} — skipping delta Parquet egress",
                        table, segment.getSiteId());
                return;
            }
            byte[] parquet;
            DecimalDegradeTally nonFinite = new DecimalDegradeTally();
            try {
                parquet = metrics.timeEgressPhase("write",
                        () -> DeltaParquetWriter.toDeltaParquet(table, schema, tableRecords,
                                parquetProperties.rowGroupBytes(), nonFinite));
            } catch (RuntimeException e) {
                // One poison table (data the declared schema cannot render) must not wedge the
                // queue: without this the whole segment stays pending and the sweep retries it
                // forever, blocking every other table — the skip-and-continue contract
                // CheckpointService documents. Render only: an upload failure is transient and
                // must keep the segment pending for the sweep, so it stays outside the catch.
                log.error("Delta Parquet render failed for table {} of site {} (seq {}..{}) — skipping "
                                + "the table's delta file (check the declared schema against the data)",
                        table, segment.getSiteId(), segment.getFirstSeq(), segment.getLastSeq(), e);
                return;
            }
            metrics.timeEgressPhase("upload", () -> storage.uploadDelta(
                    segment.getSiteId(), table, segment.getFirstSeq(),
                    segment.getLastSeq(), parquet));
            // After the upload, not before it (issue #215, review round 2). It buys less than the
            // completed-batch path's equivalent, and the difference is worth stating: uploadDelta
            // sits outside the per-table catch, so a failure on a later table propagates, the
            // segment is never marked, and the sweep re-renders every table -- re-counting the ones
            // that already uploaded. The ordering removes the re-count for the failing table only
            // (review round 3).
            metrics.unrepresentableDecimalsDegraded(nonFinite.nonFiniteCount(), false);
            metrics.unrepresentableDecimalsDegraded(nonFinite.malformedCount(), true);
        });

        segment.markEgressed();
        segmentRepository.save(segment);
        metrics.segmentEgressed();

        log.info("Delta egress done: siteId={}, seq={}..{}, tables={}",
                segment.getSiteId(), segment.getFirstSeq(), segment.getLastSeq(), byTable.keySet());
    }
}

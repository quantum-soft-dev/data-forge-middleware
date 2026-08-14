package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.TableSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public DeltaEgressService(ChangelogSegmentRepository segmentRepository,
                              ChangelogSegmentService changelogSegmentService,
                              SiteSchemaService siteSchemaService,
                              S3CheckpointStorage storage,
                              DeltaMetrics metrics) {
        this.segmentRepository = segmentRepository;
        this.changelogSegmentService = changelogSegmentService;
        this.siteSchemaService = siteSchemaService;
        this.storage = storage;
        this.metrics = metrics;
    }

    /**
     * Claim and materialize the next pending segment (per-site head, {@code SKIP LOCKED}); the
     * claim and the egress share one transaction, so a crash rolls the segment back to pending.
     *
     * @return {@code true} if a segment was processed, {@code false} when the queue is empty
     */
    @Transactional
    public boolean egressNextPending() {
        List<ChangelogSegment> next = segmentRepository.findNextPendingEgress(1);
        if (next.isEmpty()) {
            return false;
        }
        egressSegment(next.get(0));
        return true;
    }

    /**
     * Write the segment's delta Parquet files and mark it egressed.
     *
     * @param segment the committed changelog segment to materialize
     */
    @Transactional
    public void egressSegment(ChangelogSegment segment) {
        metrics.timeEgress(() -> egressSegmentTimed(segment));
    }

    private void egressSegmentTimed(ChangelogSegment segment) {
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
            try {
                parquet = metrics.timeEgressPhase("write",
                        () -> DeltaParquetWriter.toDeltaParquet(table, schema, tableRecords));
            } catch (RuntimeException e) {
                // One poison table (data the declared schema cannot render) must not wedge the
                // queue: without this the whole segment rolls back and the sweep retries it forever,
                // blocking every other table — the skip-and-continue contract CheckpointService
                // documents. Render only: an upload failure is transient and must keep the segment
                // pending for the sweep, so it stays outside the catch.
                log.error("Delta Parquet render failed for table {} of site {} (seq {}..{}) — skipping "
                                + "the table's delta file (check the declared schema against the data)",
                        table, segment.getSiteId(), segment.getFirstSeq(), segment.getLastSeq(), e);
                return;
            }
            metrics.timeEgressPhase("upload", () -> storage.uploadDelta(
                    segment.getSiteId(), table, segment.getFirstSeq(),
                    segment.getLastSeq(), parquet));
        });

        segment.markEgressed();
        segmentRepository.save(segment);
        metrics.segmentEgressed();

        log.info("Delta egress done: siteId={}, seq={}..{}, tables={}",
                segment.getSiteId(), segment.getFirstSeq(), segment.getLastSeq(), byTable.keySet());
    }
}

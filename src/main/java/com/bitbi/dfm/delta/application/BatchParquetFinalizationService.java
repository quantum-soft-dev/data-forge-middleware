package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.BatchParquetArtifact;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.TableChangeStats;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.TableSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/** Durable, idempotent finalization of one completed-batch Parquet per table (036, issue #93). */
@Service
public class BatchParquetFinalizationService {

    private static final Logger log = LoggerFactory.getLogger(BatchParquetFinalizationService.class);

    private final BatchParquetArtifactRepository artifactRepository;
    private final ChangelogSegmentRepository segmentRepository;
    private final ChangelogSegmentService segmentService;
    private final SiteSchemaService schemaService;
    private final S3CheckpointStorage storage;
    private final Path tempDirectory;
    private final long maxTempBytes;
    private final int retryDelaySeconds;

    public BatchParquetFinalizationService(
            BatchParquetArtifactRepository artifactRepository,
            ChangelogSegmentRepository segmentRepository,
            ChangelogSegmentService segmentService,
            SiteSchemaService schemaService,
            S3CheckpointStorage storage,
            @Value("${delta.batch-parquet.temp-dir:${java.io.tmpdir}}") String tempDirectory,
            @Value("${delta.batch-parquet.max-temp-bytes:10737418240}") long maxTempBytes,
            @Value("${delta.batch-parquet.retry-delay-seconds:60}") int retryDelaySeconds) {
        this.artifactRepository = artifactRepository;
        this.segmentRepository = segmentRepository;
        this.segmentService = segmentService;
        this.schemaService = schemaService;
        this.storage = storage;
        this.tempDirectory = Path.of(tempDirectory);
        this.maxTempBytes = maxTempBytes;
        this.retryDelaySeconds = retryDelaySeconds;
    }

    /**
     * Create the durable per-table work rows inside the batch-completion transaction. Existing rows
     * make replayed completion events harmless.
     */
    @Transactional
    public int enqueueBatch(UUID batchId) {
        List<ChangelogSegment> segments = segmentRepository.findByBatchIdOrderByFirstSeq(batchId);
        if (segments.isEmpty()) {
            return 0;
        }
        UUID siteId = segments.get(0).getSiteId();
        Map<String, TableChangeStats> totals = aggregateStats(segments);
        int created = 0;
        for (String tableName : totals.keySet()) {
            Optional<BatchParquetArtifact> existing = artifactRepository
                    .findBySiteIdAndBatchIdAndTableName(siteId, batchId, tableName);
            if (existing.isEmpty()) {
                artifactRepository.save(BatchParquetArtifact.pending(batchId, siteId, tableName));
                created++;
            }
        }
        return created;
    }

    /**
     * Claim and finalize one row. The PostgreSQL row lock is held for the transaction, so replicas
     * cannot build the same logical artifact concurrently; a crash releases the lock and rolls the
     * state back to retryable. Upload completion always precedes the READY transition.
     */
    @Transactional
    public boolean finalizeNext() {
        LocalDateTime retryBefore = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(retryDelaySeconds);
        List<BatchParquetArtifact> next = artifactRepository.findNextRetryable(retryBefore, 1);
        if (next.isEmpty()) {
            return false;
        }
        BatchParquetArtifact artifact = next.get(0);
        artifact.markBuilding();
        artifactRepository.save(artifact);
        try {
            FinalizedFile finalized = buildAndUpload(artifact);
            artifact.markReady(finalized.s3Key(), finalized.rowCount(), finalized.fileSize(),
                    finalized.checksum());
            log.info("Unified batch Parquet ready: batchId={}, table={}, rows={}, bytes={}",
                    artifact.getBatchId(), artifact.getTableName(), finalized.rowCount(), finalized.fileSize());
        } catch (RuntimeException e) {
            String message = Objects.toString(e.getMessage(), e.getClass().getSimpleName());
            artifact.markFailed(message);
            log.warn("Unified batch Parquet failed: batchId={}, table={}, error={}",
                    artifact.getBatchId(), artifact.getTableName(), message);
        }
        artifactRepository.save(artifact);
        return true;
    }

    private FinalizedFile buildAndUpload(BatchParquetArtifact artifact) {
        List<ChangelogSegment> segments = segmentRepository
                .findByBatchIdOrderByFirstSeq(artifact.getBatchId());
        if (segments.isEmpty()) {
            throw new IllegalStateException("Batch has no published changelog segments");
        }
        TableSchema schema = schemaService.getTableSchemas(artifact.getSiteId())
                .get(artifact.getTableName());
        if (schema == null) {
            throw new IllegalStateException("No declared schema for table " + artifact.getTableName());
        }

        Path tempFile = null;
        try {
            Files.createDirectories(tempDirectory);
            tempFile = Files.createTempFile(tempDirectory,
                    "batch-parquet-" + artifact.getId() + "-", ".parquet");
            Path output = tempFile;
            DeltaParquetWriter.FileWriteResult result = DeltaParquetWriter.writeDeltaParquet(
                    output, artifact.getTableName(), schema,
                    consumer -> segments.forEach(segment ->
                            segmentService.forEachRecord(segment.getS3Key(), consumer)));
            long expectedRows = aggregateStats(segments).getOrDefault(
                    artifact.getTableName(), new TableChangeStats(0, 0, 0)).total();
            if (result.rowCount() != expectedRows) {
                throw new IllegalStateException("Artifact row count " + result.rowCount()
                        + " does not match segment stats " + expectedRows);
            }
            if (result.fileSize() > maxTempBytes) {
                throw new IllegalStateException("Artifact exceeds temp-file limit of " + maxTempBytes + " bytes");
            }
            String s3Key = storage.uploadBatchParquet(artifact.getSiteId(), artifact.getBatchId(),
                    artifact.getTableName(), output);
            return new FinalizedFile(s3Key, result.rowCount(), result.fileSize(), result.checksum());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create unified Parquet temp file", e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.warn("Could not delete unified Parquet temp file {}", tempFile, e);
                }
            }
        }
    }

    private static Map<String, TableChangeStats> aggregateStats(List<ChangelogSegment> segments) {
        Map<String, TableChangeStats> totals = new TreeMap<>();
        segments.stream().map(ChangelogSegment::getStats).filter(Objects::nonNull)
                .flatMap(stats -> stats.entrySet().stream())
                .forEach(entry -> totals.merge(entry.getKey(), entry.getValue(), (left, right) ->
                        new TableChangeStats(left.inserts() + right.inserts(),
                                left.updates() + right.updates(), left.deletes() + right.deletes())));
        return totals;
    }

    private record FinalizedFile(String s3Key, long rowCount, long fileSize, String checksum) {
    }
}

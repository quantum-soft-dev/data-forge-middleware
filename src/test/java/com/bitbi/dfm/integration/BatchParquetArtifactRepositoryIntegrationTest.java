package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.domain.BatchParquetArtifact;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V49 manifest persistence, retry queue, exact lookup, and cleanup queries. */
@Transactional
class BatchParquetArtifactRepositoryIntegrationTest extends BaseIntegrationTest {

    private static final UUID SITE_ID = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    private static final UUID BATCH_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @Autowired
    private BatchParquetArtifactRepository repository;

    @Test
    void claimsOnlyRetryableRowsAndResolvesExactManifest() {
        BatchParquetArtifact pending = repository.save(
                BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "orders"));
        BatchParquetArtifact failed = BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "items");
        failed.markBuilding();
        failed.markFailed("retry me");
        repository.save(failed);
        BatchParquetArtifact ready = BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "customers");
        ready.markBuilding();
        ready.markReady("egress/customers.parquet", 3, 100, "abc");
        repository.save(ready);

        List<BatchParquetArtifact> claimed = repository.findNextRetryable(10);

        assertEquals(List.of("items", "orders"), claimed.stream()
                .map(BatchParquetArtifact::getTableName).sorted().toList());
        assertEquals(pending.getId(), repository.findBySiteIdAndBatchIdAndTableName(
                SITE_ID, BATCH_ID, "orders").orElseThrow().getId());
        assertTrue(repository.findBySiteIdAndBatchIdAndTableName(
                SITE_ID, BATCH_ID, "absent").isEmpty());
        assertEquals(List.of("egress/customers.parquet"), repository.findS3KeysByBatchId(BATCH_ID));
        assertEquals(BatchParquetArtifactStatus.READY,
                repository.findBySiteIdAndBatchIdAndTableName(
                        SITE_ID, BATCH_ID, "customers").orElseThrow().getStatus());
    }

    @Test
    void batchCleanupDeletesAllManifestRows() {
        repository.save(BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "orders"));
        repository.save(BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "items"));

        assertEquals(2, repository.deleteByBatchId(BATCH_ID));
        assertTrue(repository.findByBatchId(BATCH_ID).isEmpty());
    }
}

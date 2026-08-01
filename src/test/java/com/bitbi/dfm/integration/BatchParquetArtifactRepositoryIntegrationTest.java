package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.domain.BatchParquetArtifact;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        List<UUID> claimed = repository.findNextRetryable(
                        LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1), 0, 3600, 500).stream()
                .map(BatchParquetArtifact::getId).toList();

        assertTrue(claimed.contains(pending.getId()), "a pending row is claimable");
        assertTrue(claimed.contains(failed.getId()), "a cooled-down failure is claimable");
        assertFalse(claimed.contains(ready.getId()), "a published row is never rebuilt");
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
    void neverClaimsAnAbandonedArtifactAgain() {
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "orders");
        artifact.markBuilding();
        artifact.markAbandoned("no declared schema");
        repository.save(artifact);

        assertFalse(isClaimed(artifact, LocalDateTime.now(ZoneOffset.UTC).plusDays(1), 0, 0),
                "an artifact that used up its attempts is terminal, not retryable");
    }

    @Test
    void backsOffLongerAfterEachFailedAttempt() {
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "orders");
        artifact.markBuilding();
        artifact.markBuilding();
        artifact.markBuilding();
        artifact.markFailed("s3 unavailable");
        repository.save(artifact);
        // attempt_count = 3 → the base delay is multiplied by 2^2.
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1);

        assertFalse(isClaimed(artifact, now, 60, 3600),
                "one base delay is not enough after three attempts");
        assertTrue(isClaimed(artifact, now.plusSeconds(4 * 60), 60, 3600),
                "the doubled delay has elapsed");
    }

    @Test
    void reclaimsAClaimWhoseBuildLeaseExpired() {
        BatchParquetArtifact stuck = BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "orders");
        stuck.markBuilding();
        repository.save(stuck);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1);

        assertFalse(isClaimed(stuck, now, 60, 3600), "a live claim is left to its owner");
        assertTrue(isClaimed(stuck, now, 60, 0), "an expired lease makes the row claimable again");
    }

    @Test
    void renewsALeaseOnlyForTheClaimThatStillHoldsTheRow() {
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "orders");
        artifact.markBuilding();
        repository.save(artifact);
        UUID firstClaim = artifact.getClaimToken();
        LocalDateTime renewedAt = LocalDateTime.now(ZoneOffset.UTC).plusHours(1);

        assertEquals(1, repository.touchClaim(artifact.getId(), firstClaim, renewedAt));
        assertFalse(isClaimed(artifact, renewedAt.plusSeconds(1), 60, 3600),
                "a renewed lease keeps the row with its owner");
        assertEquals(0, repository.touchClaim(artifact.getId(), UUID.randomUUID(), renewedAt),
                "a stale owner cannot renew a lease it no longer holds");
    }

    /**
     * Whether the claim query would hand this specific row out. The suite shares one database and
     * other classes leave manifest rows behind, so an assertion on the whole result set is not
     * this test's to make.
     */
    private boolean isClaimed(BatchParquetArtifact artifact, LocalDateTime now,
                              int retryDelaySeconds, int leaseSeconds) {
        return repository.findNextRetryable(now, retryDelaySeconds, leaseSeconds, 500).stream()
                .anyMatch(claimed -> claimed.getId().equals(artifact.getId()));
    }

    @Test
    void batchCleanupDeletesAllManifestRows() {
        repository.save(BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "orders"));
        repository.save(BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "items"));

        assertEquals(2, repository.deleteByBatchId(BATCH_ID));
        assertTrue(repository.findByBatchId(BATCH_ID).isEmpty());
    }
}

package com.bitbi.dfm.delta.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchParquetArtifactTest {

    @Test
    void keepsTheLegacyStableKeyAsACompatibilityFallback() {
        UUID batchId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(batchId, siteId, "sales orders");

        assertEquals("egress/%s/batches/%s/sales%%20orders.parquet".formatted(siteId, batchId),
                artifact.expectedS3Key());
    }

    @Test
    void derivesDistinctClaimAttemptKeysUnderOneBatchPrefix() {
        UUID batchId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        UUID firstClaim = UUID.randomUUID();
        UUID secondClaim = UUID.randomUUID();

        String first = BatchParquetArtifactKey.attempt(siteId, batchId, "sales/orders", firstClaim);
        String second = BatchParquetArtifactKey.attempt(siteId, batchId, "sales/orders", secondClaim);

        assertEquals("egress/%s/batches/%s/".formatted(siteId, batchId),
                BatchParquetArtifactKey.batchPrefix(siteId, batchId));
        assertEquals("egress/%s/batches/%s/attempts/%s/sales%%2Forders.parquet"
                        .formatted(siteId, batchId, firstClaim), first);
        assertEquals("egress/%s/batches/%s/attempts/%s/sales%%2Forders.parquet"
                        .formatted(siteId, batchId, secondClaim), second);
        assertNotEquals(first, second);
    }

    @Test
    void lifecyclePublishesMetadataOnlyAfterBuilding() {
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(
                UUID.randomUUID(), UUID.randomUUID(), "orders");

        assertEquals(BatchParquetArtifactStatus.PENDING, artifact.getStatus());
        assertNull(artifact.getS3Key());

        artifact.markBuilding();
        assertEquals(BatchParquetArtifactStatus.BUILDING, artifact.getStatus());
        assertEquals(1, artifact.getAttemptCount());

        artifact.markReady("egress/site/batches/batch/orders.parquet", 25_123L, 4096L, "abc123");

        assertEquals(BatchParquetArtifactStatus.READY, artifact.getStatus());
        assertEquals(25_123L, artifact.getRowCount());
        assertEquals(4096L, artifact.getFileSize());
        assertEquals("abc123", artifact.getChecksum());
        assertNull(artifact.getLastError());
        assertNull(artifact.getFirstSeq());
        assertNull(artifact.getLastSeq());
    }

    @Test
    void markReadyRecordsTheTableSeqRange() {
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(
                UUID.randomUUID(), UUID.randomUUID(), "orders");
        artifact.markBuilding();

        artifact.markReady("key", 10, 20, "hash", 100L, 250L);

        assertEquals(100L, artifact.getFirstSeq());
        assertEquals(250L, artifact.getLastSeq());
    }

    @Test
    void markReadyRejectsAnInvertedSeqRange() {
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(
                UUID.randomUUID(), UUID.randomUUID(), "orders");
        artifact.markBuilding();

        assertThrows(IllegalArgumentException.class,
                () -> artifact.markReady("key", 10, 20, "hash", 250L, 100L));
    }

    @Test
    void markReadyRejectsAPartialSeqRange() {
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(
                UUID.randomUUID(), UUID.randomUUID(), "orders");
        artifact.markBuilding();

        assertThrows(IllegalArgumentException.class,
                () -> artifact.markReady("key", 10, 20, "hash", 100L, null));
        assertThrows(IllegalArgumentException.class,
                () -> artifact.markReady("key", 10, 20, "hash", null, 250L));
    }

    @Test
    void failureIsRetryableAndClearsPublishedMetadata() {
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(
                UUID.randomUUID(), UUID.randomUUID(), "orders");
        artifact.markBuilding();
        artifact.markFailed("upload unavailable");

        assertEquals(BatchParquetArtifactStatus.FAILED, artifact.getStatus());
        assertEquals("upload unavailable", artifact.getLastError());
        assertNull(artifact.getS3Key());

        artifact.markBuilding();

        assertEquals(BatchParquetArtifactStatus.BUILDING, artifact.getStatus());
        assertEquals(2, artifact.getAttemptCount());
        assertNull(artifact.getLastError());
    }

    @Test
    void abandonedArtifactIsTerminalAndNoLongerRetryable() {
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(
                UUID.randomUUID(), UUID.randomUUID(), "orders");
        artifact.markBuilding();
        artifact.markFailed("upload unavailable");

        assertTrue(artifact.isRetryable(), "a plain failure is still worth another attempt");

        artifact.markBuilding();
        artifact.markAbandoned("no declared schema");

        assertEquals(BatchParquetArtifactStatus.ABANDONED, artifact.getStatus());
        assertFalse(artifact.isRetryable());
        assertThrows(IllegalStateException.class, artifact::markBuilding);
    }

    @Test
    void anExpiredClaimCanBeTakenOverWithoutLosingTheAttemptItSpent() {
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(
                UUID.randomUUID(), UUID.randomUUID(), "orders");
        artifact.markBuilding();

        // The owner died mid-build; the lease lapsed and another worker reclaims the row.
        artifact.markBuilding();

        assertEquals(BatchParquetArtifactStatus.BUILDING, artifact.getStatus());
        assertEquals(2, artifact.getAttemptCount(), "the lost attempt still counts against the cap");
    }

    @Test
    void readyArtifactCannotBeClaimedOrFailedAgain() {
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(
                UUID.randomUUID(), UUID.randomUUID(), "orders");
        artifact.markBuilding();
        artifact.markReady("key", 1, 2, "hash");

        assertThrows(IllegalStateException.class, artifact::markBuilding);
        assertThrows(IllegalStateException.class, () -> artifact.markFailed("late failure"));
    }

    @Test
    void markAbandonedRecordsTheSeqRangeWhenProvided() {
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(
                UUID.randomUUID(), UUID.randomUUID(), "orders");
        artifact.markBuilding();

        artifact.markAbandoned("gave up", 100L, 250L, artifact.getUpdatedAt());

        assertEquals(BatchParquetArtifactStatus.ABANDONED, artifact.getStatus());
        assertNull(artifact.getS3Key());
        assertEquals(100L, artifact.getFirstSeq());
        assertEquals(250L, artifact.getLastSeq());

        artifact.requeue();

        assertEquals(100L, artifact.getFirstSeq(), "requeue must not wipe a stored abandon range");
        assertEquals(250L, artifact.getLastSeq());
    }

    @Test
    void requeueResetsAnAbandonedArtifactToANewPendingLifecycle() {
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(
                UUID.randomUUID(), UUID.randomUUID(), "orders");
        artifact.markBuilding();
        artifact.markBuilding();
        artifact.markAbandoned("schema mismatch");

        artifact.requeue();

        assertEquals(BatchParquetArtifactStatus.PENDING, artifact.getStatus());
        assertEquals(0, artifact.getAttemptCount());
        assertNull(artifact.getClaimToken());
        assertNull(artifact.getLastError());
        assertNull(artifact.getS3Key());
        assertNull(artifact.getRowCount());
        assertNull(artifact.getFileSize());
        assertNull(artifact.getChecksum());
        assertNull(artifact.getReadyAt());
        assertTrue(artifact.isRetryable());
    }

    @Test
    void requeueAcceptsBuildingButRejectsStatesThatNeedNoOperatorRecovery() {
        BatchParquetArtifact building = BatchParquetArtifact.pending(
                UUID.randomUUID(), UUID.randomUUID(), "orders");
        building.markBuilding();

        building.requeue();

        assertEquals(BatchParquetArtifactStatus.PENDING, building.getStatus());
        assertEquals(0, building.getAttemptCount());
        assertNull(building.getClaimToken());

        BatchParquetArtifact pending = BatchParquetArtifact.pending(
                UUID.randomUUID(), UUID.randomUUID(), "customers");
        assertThrows(IllegalStateException.class, pending::requeue);

        BatchParquetArtifact failed = BatchParquetArtifact.pending(
                UUID.randomUUID(), UUID.randomUUID(), "items");
        failed.markBuilding();
        failed.markFailed("transient");
        assertThrows(IllegalStateException.class, failed::requeue);

        BatchParquetArtifact ready = BatchParquetArtifact.pending(
                UUID.randomUUID(), UUID.randomUUID(), "products");
        ready.markBuilding();
        ready.markReady("key", 1, 2, "hash");
        assertThrows(IllegalStateException.class, ready::requeue);
    }
}

package com.bitbi.dfm.delta.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BatchParquetArtifactTest {

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
    void readyArtifactCannotBeClaimedOrFailedAgain() {
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(
                UUID.randomUUID(), UUID.randomUUID(), "orders");
        artifact.markBuilding();
        artifact.markReady("key", 1, 2, "hash");

        assertThrows(IllegalStateException.class, artifact::markBuilding);
        assertThrows(IllegalStateException.class, () -> artifact.markFailed("late failure"));
    }
}

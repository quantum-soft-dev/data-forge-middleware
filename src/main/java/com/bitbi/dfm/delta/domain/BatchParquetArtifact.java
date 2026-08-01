package com.bitbi.dfm.delta.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable manifest and work item for one completed batch's unified table Parquet (036, issue #93).
 * Metadata remains absent until the complete S3 object is ready for download.
 */
@Entity
@Table(name = "batch_parquet_artifacts")
@Getter
@NoArgsConstructor
public class BatchParquetArtifact {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "batch_id", nullable = false, updatable = false)
    private UUID batchId;

    @Column(name = "site_id", nullable = false, updatable = false)
    private UUID siteId;

    @Column(name = "table_name", nullable = false, updatable = false, length = 255)
    private String tableName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private BatchParquetArtifactStatus status;

    @Column(name = "s3_key", length = 1000)
    private String s3Key;

    @Column(name = "row_count")
    private Long rowCount;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "checksum", length = 64)
    private String checksum;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "ready_at")
    private LocalDateTime readyAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public static BatchParquetArtifact pending(UUID batchId, UUID siteId, String tableName) {
        BatchParquetArtifact artifact = new BatchParquetArtifact();
        artifact.id = UUID.randomUUID();
        artifact.batchId = Objects.requireNonNull(batchId, "batchId");
        artifact.siteId = Objects.requireNonNull(siteId, "siteId");
        artifact.tableName = Objects.requireNonNull(tableName, "tableName");
        artifact.status = BatchParquetArtifactStatus.PENDING;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        artifact.createdAt = now;
        artifact.updatedAt = now;
        return artifact;
    }

    /** Claim a pending or failed artifact for another deterministic build attempt. */
    public void markBuilding() {
        if (status != BatchParquetArtifactStatus.PENDING && status != BatchParquetArtifactStatus.FAILED) {
            throw new IllegalStateException("Cannot build artifact in status " + status);
        }
        status = BatchParquetArtifactStatus.BUILDING;
        attemptCount++;
        clearPublishedMetadata();
        lastError = null;
        touch();
    }

    /** Publish immutable metadata only after the stable S3 object has completed uploading. */
    public void markReady(String key, long rows, long bytes, String sha256) {
        requireBuilding();
        s3Key = Objects.requireNonNull(key, "key");
        rowCount = rows;
        fileSize = bytes;
        checksum = Objects.requireNonNull(sha256, "sha256");
        status = BatchParquetArtifactStatus.READY;
        lastError = null;
        readyAt = LocalDateTime.now(ZoneOffset.UTC);
        updatedAt = readyAt;
    }

    /** Record a retryable, table-local failure without publishing object metadata. */
    public void markFailed(String error) {
        requireBuilding();
        status = BatchParquetArtifactStatus.FAILED;
        clearPublishedMetadata();
        lastError = Objects.requireNonNull(error, "error");
        touch();
    }

    private void requireBuilding() {
        if (status != BatchParquetArtifactStatus.BUILDING) {
            throw new IllegalStateException("Artifact is not BUILDING: " + status);
        }
    }

    private void clearPublishedMetadata() {
        s3Key = null;
        rowCount = null;
        fileSize = null;
        checksum = null;
        readyAt = null;
    }

    private void touch() {
        updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = BatchParquetArtifactStatus.PENDING;
        }
    }
}

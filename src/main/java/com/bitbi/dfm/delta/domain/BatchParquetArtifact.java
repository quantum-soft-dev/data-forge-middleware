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

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "ready_at")
    private LocalDateTime readyAt;

    /**
     * Inclusive first sequence of this table in the batch. Set on {@link #markReady} and kept
     * through abandon/requeue so the catalog does not depend on live changelog segments.
     */
    @Column(name = "first_seq")
    private Long firstSeq;

    /**
     * Inclusive last sequence of this table in the batch. Set on {@link #markReady} and kept
     * through abandon/requeue so the catalog does not depend on live changelog segments.
     */
    @Column(name = "last_seq")
    private Long lastSeq;

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

    /**
     * Claim this artifact for another deterministic build attempt.
     *
     * <p>The claim is committed on its own, before the build runs, so {@code attemptCount} counts
     * attempts that <em>started</em> rather than attempts that returned an answer — a process that
     * dies mid-build has still used one up. {@code BUILDING} is therefore a legal source state: it
     * means the previous claimant went away and its build lease expired.</p>
     *
     * <p>Each claim mints a fresh {@link #getClaimToken() token}. The previous owner may still be
     * running — a lease lapses on elapsed time, not on proof of death — and the token is what stops
     * its late result from landing in this attempt.</p>
     */
    public void markBuilding() {
        if (status == BatchParquetArtifactStatus.READY || status == BatchParquetArtifactStatus.ABANDONED) {
            throw new IllegalStateException("Cannot build artifact in status " + status);
        }
        status = BatchParquetArtifactStatus.BUILDING;
        attemptCount++;
        claimToken = UUID.randomUUID();
        clearPublishedMetadata();
        lastError = null;
        touch();
    }

    /** @return true when {@code token} identifies the claim this row currently holds. */
    public boolean isHeldBy(UUID token) {
        return status == BatchParquetArtifactStatus.BUILDING && claimToken != null
                && claimToken.equals(token);
    }

    /** Publish immutable metadata only after the stable S3 object has completed uploading. */
    public void markReady(String key, long rows, long bytes, String sha256) {
        markReady(key, rows, bytes, sha256, null, null);
    }

    /**
     * Publish metadata and the inclusive seq range covered by this table's file.
     * Both bounds null means "unknown" (legacy rows); a partial pair is rejected.
     */
    public void markReady(String key, long rows, long bytes, String sha256, Long firstSeq, Long lastSeq) {
        requireBuilding();
        if ((firstSeq == null) != (lastSeq == null)) {
            throw new IllegalArgumentException("firstSeq and lastSeq must both be set or both be null");
        }
        if (firstSeq != null && lastSeq < firstSeq) {
            throw new IllegalArgumentException("lastSeq must be >= firstSeq");
        }
        s3Key = Objects.requireNonNull(key, "key");
        rowCount = rows;
        fileSize = bytes;
        checksum = Objects.requireNonNull(sha256, "sha256");
        this.firstSeq = firstSeq;
        this.lastSeq = lastSeq;
        status = BatchParquetArtifactStatus.READY;
        lastError = null;
        claimToken = null;
        readyAt = LocalDateTime.now(ZoneOffset.UTC);
        updatedAt = readyAt;
    }

    /** Record a retryable, table-local failure without publishing object metadata. */
    public void markFailed(String error) {
        requireBuilding();
        status = BatchParquetArtifactStatus.FAILED;
        clearPublishedMetadata();
        lastError = Objects.requireNonNull(error, "error");
        claimToken = null;
        touch();
    }

    /**
     * Give up on this table after its attempts ran out. Terminal — the claim query skips it and the
     * download answers 404, which separates "we stopped trying" from a failure still being retried.
     */
    public void markAbandoned(String error) {
        requireBuilding();
        status = BatchParquetArtifactStatus.ABANDONED;
        clearPublishedMetadata();
        lastError = Objects.requireNonNull(error, "error");
        claimToken = null;
        touch();
    }

    /** @return true while a worker is expected to make another attempt. */
    public boolean isRetryable() {
        return status == BatchParquetArtifactStatus.PENDING
                || status == BatchParquetArtifactStatus.BUILDING
                || status == BatchParquetArtifactStatus.FAILED;
    }

    /**
     * Reset a terminal or displaced build to the start of a fresh operator-authorized lifecycle.
     * Whether a {@code BUILDING} lease is old enough to displace is an application policy; the
     * aggregate only enforces the two states for which recovery is meaningful.
     */
    public void requeue() {
        if (status != BatchParquetArtifactStatus.ABANDONED
                && status != BatchParquetArtifactStatus.BUILDING) {
            throw new IllegalStateException("Cannot requeue artifact in status " + status);
        }
        status = BatchParquetArtifactStatus.PENDING;
        attemptCount = 0;
        claimToken = null;
        lastError = null;
        clearPublishedMetadata();
        touch();
    }

    /** @return the stable object key cleanup must remove even before metadata is published. */
    public String expectedS3Key() {
        return BatchParquetArtifactKey.of(siteId, batchId, tableName);
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

package com.bitbi.dfm.batch.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;

/**
 * Batch aggregate root representing a file upload session.
 * <p>
 * Enforces "one active batch per site" constraint and manages lifecycle state transitions.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Entity
@Table(name = "batches")
@Getter
@NoArgsConstructor
public class Batch {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private BatchStatus status;

    @Column(name = "s3_path", nullable = false, length = 500)
    private String s3Path;

    @Column(name = "uploaded_files_count", nullable = false)
    private Integer uploadedFilesCount;

    @Column(name = "total_size", nullable = false)
    private Long totalSize;

    @Column(name = "has_errors", nullable = false)
    private Boolean hasErrors;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Batch(UUID id, UUID accountId, UUID siteId, BatchStatus status, String s3Path,
                    Integer uploadedFilesCount, Long totalSize, Boolean hasErrors,
                    LocalDateTime startedAt, LocalDateTime completedAt, LocalDateTime createdAt) {
        this.id = id;
        this.accountId = accountId;
        this.siteId = siteId;
        this.status = status;
        this.s3Path = s3Path;
        this.uploadedFilesCount = uploadedFilesCount;
        this.totalSize = totalSize;
        this.hasErrors = hasErrors;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.createdAt = createdAt;
    }

    public static Batch start(UUID accountId, UUID siteId, String domain) {
        Objects.requireNonNull(siteId, "SiteId cannot be null");
        Objects.requireNonNull(accountId, "AccountId cannot be null");
        Objects.requireNonNull(domain, "Domain cannot be null");

        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        String s3Path = generateS3Path(accountId, domain, now);

        return new Batch(id, accountId, siteId, BatchStatus.IN_PROGRESS, s3Path,
                0, 0L, false, now, null, now);
    }

    private static String generateS3Path(UUID accountId, String domain, LocalDateTime timestamp) {
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH-mm");
        String date = timestamp.format(dateFormatter);
        String time = timestamp.format(timeFormatter);
        return String.format("%s/%s/%s/%s/", accountId, domain, date, time);
    }

    public void complete() {
        status.validateTransition(BatchStatus.COMPLETED);
        this.status = BatchStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    public void fail() {
        status.validateTransition(BatchStatus.FAILED);
        this.status = BatchStatus.FAILED;
        this.hasErrors = true;
        this.completedAt = LocalDateTime.now();
    }

    public void cancel() {
        status.validateTransition(BatchStatus.CANCELLED);
        this.status = BatchStatus.CANCELLED;
        this.completedAt = LocalDateTime.now();
    }

    public void markAsNotCompleted() {
        status.validateTransition(BatchStatus.NOT_COMPLETED);
        this.status = BatchStatus.NOT_COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    public void incrementFileCount(long fileSize) {
        if (!status.allowsFileUpload()) {
            throw new IllegalStateException("Cannot upload files to batch in status: " + status);
        }
        this.uploadedFilesCount++;
        this.totalSize += fileSize;
    }

    public void markAsHavingErrors() {
        this.hasErrors = true;
    }

    public boolean isExpired(int timeoutMinutes) {
        if (status != BatchStatus.IN_PROGRESS) {
            return false;
        }
        LocalDateTime expirationTime = startedAt.plusMinutes(timeoutMinutes);
        return LocalDateTime.now().isAfter(expirationTime);
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Batch batch)) return false;
        return Objects.equals(id, batch.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}

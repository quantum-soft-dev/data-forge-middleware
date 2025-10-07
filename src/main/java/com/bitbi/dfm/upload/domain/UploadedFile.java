package com.bitbi.dfm.upload.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * UploadedFile entity (part of Batch aggregate).
 * <p>
 * Stores metadata for each file uploaded to a batch.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Entity
@Table(name = "uploaded_files")
@Getter
@NoArgsConstructor
public class UploadedFile {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "original_file_name", nullable = false, length = 500)
    private String originalFileName;

    @Column(name = "s3_key", nullable = false, unique = true, length = 1000)
    private String s3Key;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "checksum", nullable = false, length = 64)
    private String checksum;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    protected UploadedFile(UUID id, UUID batchId, String originalFileName, String s3Key,
                           Long fileSize, String contentType, String checksum, LocalDateTime uploadedAt) {
        this.id = id;
        this.batchId = batchId;
        this.originalFileName = originalFileName;
        this.s3Key = s3Key;
        this.fileSize = fileSize;
        this.contentType = contentType;
        this.checksum = checksum;
        this.uploadedAt = uploadedAt;
    }

    public static UploadedFile create(UUID batchId, String originalFileName, String s3Path,
                                      Long fileSize, String contentType, FileChecksum fileChecksum) {
        Objects.requireNonNull(batchId, "BatchId cannot be null");
        Objects.requireNonNull(originalFileName, "OriginalFileName cannot be null");
        Objects.requireNonNull(s3Path, "S3Path cannot be null");
        Objects.requireNonNull(fileSize, "FileSize cannot be null");
        Objects.requireNonNull(contentType, "ContentType cannot be null");
        Objects.requireNonNull(fileChecksum, "FileChecksum cannot be null");

        if (fileSize <= 0) {
            throw new IllegalArgumentException("FileSize must be positive");
        }

        UUID id = UUID.randomUUID();
        String s3Key = s3Path + originalFileName;
        LocalDateTime now = LocalDateTime.now();

        return new UploadedFile(id, batchId, originalFileName, s3Key, fileSize,
                contentType, fileChecksum.value(), now);
    }

    @PrePersist
    protected void onCreate() {
        if (uploadedAt == null) uploadedAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UploadedFile that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}

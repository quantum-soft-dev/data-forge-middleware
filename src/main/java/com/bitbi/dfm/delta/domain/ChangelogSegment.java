package com.bitbi.dfm.delta.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Metadata for one persisted changelog segment (Delta Client v2 — 022).
 *
 * <p>A segment is the immutable set of accepted change records of a single session, stored
 * append-only in object storage ({@code s3Key}). This row records its sequence range, record
 * count, content hash, and originating batch.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Entity
@Table(name = "changelog_segments")
@Getter
@NoArgsConstructor
public class ChangelogSegment {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "first_seq", nullable = false)
    private Long firstSeq;

    @Column(name = "last_seq", nullable = false)
    private Long lastSeq;

    @Column(name = "record_count", nullable = false)
    private Long recordCount;

    @Column(name = "content_hash", nullable = false, length = 128)
    private String contentHash;

    @Column(name = "s3_key", nullable = false, length = 1000)
    private String s3Key;

    @Column(name = "mode", nullable = false, length = 20)
    private String mode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Create a changelog segment record.
     */
    public static ChangelogSegment create(UUID siteId, UUID batchId, long firstSeq, long lastSeq,
                                          long recordCount, String contentHash, String s3Key, String mode) {
        ChangelogSegment segment = new ChangelogSegment();
        segment.id = UUID.randomUUID();
        segment.siteId = siteId;
        segment.batchId = batchId;
        segment.firstSeq = firstSeq;
        segment.lastSeq = lastSeq;
        segment.recordCount = recordCount;
        segment.contentHash = contentHash;
        segment.s3Key = s3Key;
        segment.mode = mode;
        segment.createdAt = LocalDateTime.now();
        return segment;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}

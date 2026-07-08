package com.bitbi.dfm.delta.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
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
     * Per-table insert/update/delete counts for this segment's records, keyed by table name.
     * Nullable: pre-existing segments (before this field was added) have no stats — batch history
     * simply shows no per-table breakdown for them.
     */
    @Type(JsonBinaryType.class)
    @Column(name = "stats", columnDefinition = "jsonb")
    private Map<String, TableChangeStats> stats;

    /**
     * When the segment's delta Parquet egress was materialized; {@code null} = pending (the
     * segment is the durable egress work queue entry, Task 8).
     */
    @Column(name = "egress_at")
    private LocalDateTime egressAt;

    /**
     * Create a changelog segment record.
     *
     * @param stats per-table insert/update/delete counts, or {@code null} if not computed
     */
    public static ChangelogSegment create(UUID siteId, UUID batchId, long firstSeq, long lastSeq,
                                          long recordCount, String contentHash, String s3Key, String mode,
                                          Map<String, TableChangeStats> stats) {
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
        segment.stats = stats;
        segment.createdAt = LocalDateTime.now(ZoneOffset.UTC);
        return segment;
    }

    /**
     * Mark the segment's delta Parquet egress as done (removes it from the pending queue).
     */
    public void markEgressed() {
        this.egressAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}

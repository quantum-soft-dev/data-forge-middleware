package com.bitbi.dfm.batch.domain;

import com.bitbi.dfm.upload.domain.UploadedFile;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
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

    /**
     * Last ingestion activity of a Delta v2 streaming session (029). Always {@code null} for
     * v1 file-upload batches; the timeout sweeper falls back to {@code startedAt} when null.
     */
    @Column(name = "last_activity_at")
    private LocalDateTime lastActivityAt;

    /**
     * Delta v2 session mode of this batch (029: batch = one session); {@code null} for batches
     * started without one. Recorded at session start so a FULL_SNAPSHOT can be told apart from an
     * ordinary delta session while it is still uploading (issue #84) — the mode used to live only
     * in the gRPC stream's heap.
     */
    @Column(name = "session_mode", length = 20)
    private String sessionMode;

    // ---- Delta v2 session totals (issue #346) --------------------------------------------------
    //
    // The session's history, kept on the batch because the changelog segments it used to be
    // computed from are working units that retention deletes below the checkpoint. A NULL
    // total_records means "not tracked": a row written before V58, or started by a pre-V58 pod
    // during a rolling deploy, whose earlier segments were never counted — history then falls back
    // to reading the segments, exactly as before.
    //
    // updatable = false on all five: they are written only by the targeted
    // JpaBatchRepository#storeDeltaTotals, never by a whole-entity save. A transition flushing a
    // Batch it loaded before a seal recorded its segment would otherwise write the old totals back
    // (the #245 clobber), and the targeted write deliberately does not bump @Version (030).

    /** Records in the session's committed segments; {@code null} = totals not tracked. */
    @Column(name = "total_records", updatable = false)
    private Long totalRecords;

    /** Distinct tables in {@link #tableStats}. */
    @Column(name = "table_count", updatable = false)
    private Integer tableCount;

    /** Per-table insert/update/delete counts across the session. */
    @Type(JsonBinaryType.class)
    @Column(name = "table_stats", columnDefinition = "jsonb", updatable = false)
    private Map<String, BatchTableStats> tableStats;

    /** Lowest sequence number recorded; {@code null} until a segment is recorded. */
    @Column(name = "first_seq", updatable = false)
    private Long firstSeq;

    /** Highest sequence number recorded; {@code null} until a segment is recorded. */
    @Column(name = "last_seq", updatable = false)
    private Long lastSeq;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @OneToMany(mappedBy = "batchId", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<UploadedFile> uploadedFiles = new ArrayList<>();

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
        // version is automatically managed by JPA
    }

    /**
     * Start a new batch (Auth V2 - uses siteId in S3 path).
     *
     * @param accountId account identifier
     * @param siteId    site identifier (used in S3 path)
     * @return new batch in IN_PROGRESS status
     */
    public static Batch start(UUID accountId, UUID siteId) {
        return start(accountId, siteId, null);
    }

    /**
     * Start a new batch for a Delta v2 session, recording the session mode (issue #84).
     *
     * @param accountId   account identifier
     * @param siteId      site identifier (used in S3 path)
     * @param sessionMode Delta v2 session mode (FULL_SNAPSHOT, DELTA, CONTINUOUS), may be null
     * @return new batch in IN_PROGRESS status
     */
    public static Batch start(UUID accountId, UUID siteId, String sessionMode) {
        Objects.requireNonNull(siteId, "SiteId cannot be null");
        Objects.requireNonNull(accountId, "AccountId cannot be null");

        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String s3Path = generateS3Path(accountId, siteId, now);

        Batch batch = new Batch(id, accountId, siteId, BatchStatus.IN_PROGRESS, s3Path,
                0, 0L, false, now, null, now);
        batch.sessionMode = sessionMode;
        batch.totalRecords = 0L;
        batch.tableCount = 0;
        batch.tableStats = new TreeMap<>();
        return batch;
    }

    private static String generateS3Path(UUID accountId, UUID siteId, LocalDateTime timestamp) {
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH-mm");
        String date = timestamp.format(dateFormatter);
        String time = timestamp.format(timeFormatter);
        return String.format("%s/%s/%s/%s/", accountId, siteId, date, time);
    }

    public void complete() {
        status.validateTransition(BatchStatus.COMPLETED);
        this.status = BatchStatus.COMPLETED;
        this.completedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public void completeWithWarnings() {
        status.validateTransition(BatchStatus.COMPLETED_WITH_WARNINGS);
        this.status = BatchStatus.COMPLETED_WITH_WARNINGS;
        this.completedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public void fail() {
        status.validateTransition(BatchStatus.FAILED);
        this.status = BatchStatus.FAILED;
        this.hasErrors = true;
        this.completedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public void cancel() {
        status.validateTransition(BatchStatus.CANCELLED);
        this.status = BatchStatus.CANCELLED;
        this.completedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public void markAsNotCompleted() {
        status.validateTransition(BatchStatus.NOT_COMPLETED);
        this.status = BatchStatus.NOT_COMPLETED;
        this.completedAt = LocalDateTime.now(ZoneOffset.UTC);
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

    /**
     * Record live session activity on a Delta v2 streaming batch (029). The ingestion path calls
     * this at a bounded cadence (session start/resume, Ack watermark, segment seal) so the timeout
     * sweeper can distinguish a long-lived live session from a silently abandoned one.
     */
    public void touchActivity() {
        this.lastActivityAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Whether this batch carries its own Delta v2 totals (issue #346): {@code true} for every batch
     * started since V58, {@code false} for older rows, whose history is read from the segments.
     */
    public boolean tracksDeltaTotals() {
        return totalRecords != null;
    }

    /**
     * Add one committed changelog segment to the session totals (issue #346).
     * <p>
     * A no-op on an untracked batch: its earlier segments were never counted, so adding only the
     * later ones would store a partial total that reads as the whole. The caller persists the
     * result with {@code BatchRepository#storeDeltaTotals} — these columns are not written by a
     * save of the entity.
     * </p>
     *
     * @param segment the segment's contribution
     */
    public void recordDeltaSegment(BatchDeltaSegment segment) {
        if (!tracksDeltaTotals()) {
            return;
        }
        Map<String, BatchTableStats> merged = new TreeMap<>(tableStats != null ? tableStats : Map.of());
        if (segment.stats() != null) {
            segment.stats().forEach((table, stats) -> merged.merge(table, stats, BatchTableStats::plus));
        }
        this.totalRecords += segment.recordCount();
        this.tableStats = merged;
        this.tableCount = merged.size();
        this.firstSeq = firstSeq == null ? segment.firstSeq() : Math.min(firstSeq, segment.firstSeq());
        this.lastSeq = lastSeq == null ? segment.lastSeq() : Math.max(lastSeq, segment.lastSeq());
    }

    public boolean isExpired(int timeoutMinutes) {
        if (status != BatchStatus.IN_PROGRESS) {
            return false;
        }
        // 029: a streaming batch is measured from its last session activity, so a long-lived live
        // session survives while a silent one still expires. v1 batches never touch activity, so
        // they keep the started_at-based timeout.
        LocalDateTime baseline = lastActivityAt != null ? lastActivityAt : startedAt;
        return LocalDateTime.now(ZoneOffset.UTC).isAfter(baseline.plusMinutes(timeoutMinutes));
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now(ZoneOffset.UTC);
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

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
     * Consecutive failed delta-Parquet egress attempts (issue #243). Advisory: it drives the
     * backoff and the poisoned reporting, never a decision to discard work.
     *
     * <p>A failure is <em>recorded</em> by a targeted
     * {@code UPDATE ... SET egress_attempts = egress_attempts + 1}, not by saving this entity, so
     * two replicas attempting the same segment both count. The column is still carried by the
     * entity, so the success path's whole-entity {@code save} of a claim-time snapshot can write a
     * stale value back — issue #245's marker clobber, which these four columns join.</p>
     */
    @Column(name = "egress_attempts", nullable = false)
    private int egressAttempts;

    /**
     * Not claimable by the egress queue before this instant; {@code null} = claimable now
     * (issue #243).
     */
    @Column(name = "egress_retry_at")
    private LocalDateTime egressRetryAt;

    /** The delta-SQL twin of {@link #egressAttempts} (issue #243). */
    @Column(name = "plugin_sql_attempts", nullable = false)
    private int pluginSqlAttempts;

    /** The delta-SQL twin of {@link #egressRetryAt} (issue #243). */
    @Column(name = "plugin_sql_retry_at")
    private LocalDateTime pluginSqlRetryAt;

    /**
     * Whether this segment belongs to a re-baseline snapshot that is still streaming (033).
     *
     * <p>A snapshot too large to buffer is sealed into bounded segments as it arrives. Those
     * segments are durable but must not be acted on: the checkpoint fold, the delta-Parquet egress
     * queue and the Bit BI SQL queue all skip them. {@code SessionEnd} flips the whole batch to
     * {@code false} in the same transaction that discards the previous baseline, so the new baseline
     * appears atomically and a drop mid-snapshot leaves the old one intact.</p>
     */
    @Column(name = "provisional", nullable = false)
    private boolean provisional = false;

    /**
     * Create a changelog segment record.
     *
     * @param stats per-table insert/update/delete counts, or {@code null} if not computed
     */
    public static ChangelogSegment create(UUID siteId, UUID batchId, long firstSeq, long lastSeq,
                                          long recordCount, String contentHash, String s3Key, String mode,
                                          Map<String, TableChangeStats> stats) {
        return create(UUID.randomUUID(), siteId, batchId, firstSeq, lastSeq, recordCount,
                contentHash, s3Key, mode, stats);
    }

    /**
     * Create a changelog segment record with a pre-generated id (029: the id is minted before the
     * S3 upload so the storage key and the row share the segment's identity).
     */
    public static ChangelogSegment create(UUID id, UUID siteId, UUID batchId, long firstSeq, long lastSeq,
                                          long recordCount, String contentHash, String s3Key, String mode,
                                          Map<String, TableChangeStats> stats) {
        ChangelogSegment segment = new ChangelogSegment();
        segment.id = id;
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
     * When the segment's records were rendered into Bit BI plugin SQL (or deliberately skipped);
     * {@code null} = pending — the segment is the durable delta-SQL work queue entry
     * (026-bitbi-delta-sql), same pattern as {@link #egressAt}.
     */
    @Column(name = "plugin_sql_at")
    private LocalDateTime pluginSqlAt;

    /**
     * Whether the Bit BI delta-SQL queue still owes this segment ({@code plugin_sql_at IS NULL}).
     *
     * <p>The domain form of the predicate the queue queries
     * ({@code findNextPendingPluginSql}), retention's prune
     * ({@code findBelowCheckpointBySiteId} / {@code deleteByIdIfProcessed}) and the batch-deletion
     * counts express in SQL — one semantics, stated here beside the {@code mark*} methods that
     * settle it. A <b>provisional</b> segment (033) answers {@code false} by construction: parking
     * sets both markers to a sentinel and publication re-{@code NULL}s them, which is what actually
     * enqueues the work — the sentinel protects the queues, while retention never sees provisional
     * rows at all because its query excludes them.</p>
     *
     * @return {@code true} while the segment is the delta-SQL queue's durable work entry
     */
    public boolean isPendingPluginSql() {
        return pluginSqlAt == null;
    }

    /**
     * Whether the delta-Parquet egress still owes this segment ({@code egress_at IS NULL}).
     * The egress twin of {@link #isPendingPluginSql()}, with the same provisional caveat.
     *
     * @return {@code true} while the segment is the egress queue's durable work entry
     */
    public boolean isPendingEgress() {
        return egressAt == null;
    }

    /**
     * Mark the segment's delta Parquet egress as done (removes it from the pending queue).
     */
    public void markEgressed() {
        this.egressAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Mark the segment's plugin SQL generation as done or deliberately skipped (removes it from
     * the pending delta-SQL queue).
     */
    public void markPluginSqlProcessed() {
        this.pluginSqlAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Hold the segment back from the fold and both work queues until its re-baseline session
     * commits (033). Set at creation; cleared in bulk for the whole batch at {@code SessionEnd}.
     *
     * <p>Both queue markers are <em>parked</em> at the same time. The queues treat {@code NULL} as
     * "pending", so parking them takes the segment out of every poller — including one running the
     * previous release, which has no notion of {@code provisional} and would otherwise claim a
     * half-streamed snapshot during a rolling deploy and stamp it processed for good. Publication
     * puts both back to {@code NULL}, which is what actually enqueues the work.</p>
     */
    public void markProvisional() {
        this.provisional = true;
        LocalDateTime parked = LocalDateTime.now(ZoneOffset.UTC);
        this.egressAt = parked;
        this.pluginSqlAt = parked;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}

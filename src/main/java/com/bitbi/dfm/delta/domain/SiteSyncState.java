package com.bitbi.dfm.delta.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Per-site delta-ingestion watermark (Delta Client v2 — feature 022).
 *
 * <p>One row per site (site_id is the primary key). Tracks the highest durably-applied
 * change sequence ({@code lastAppliedSeq}) and the latest materialized checkpoint
 * ({@code lastCheckpointSeq} / {@code lastCheckpointAt}). The client aligns its local
 * watermark to {@code lastAppliedSeq} via {@code GetSyncState}.</p>
 *
 * <p>{@code @DynamicUpdate}: the row is mutated concurrently by independent single-field writers
 * (ingestion watermark, checkpoint pointer, rebaseline/rebuild request flags) with no
 * {@code @Version}. A full-row flush would let a transaction that loaded the row earlier write
 * back stale values of the fields it never touched — e.g. an ingestion commit silently dropping
 * a just-acknowledged rebaseline flag (review r3). Dynamic updates confine each flush to the
 * dirty columns.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Entity
@Table(name = "site_sync_state")
@org.hibernate.annotations.DynamicUpdate
@Getter
@NoArgsConstructor
public class SiteSyncState {

    @Id
    @Column(name = "site_id", updatable = false, nullable = false)
    private UUID siteId;

    @Column(name = "last_applied_seq", nullable = false)
    private Long lastAppliedSeq;

    @Column(name = "last_checkpoint_seq", nullable = false)
    private Long lastCheckpointSeq;

    @Column(name = "last_checkpoint_at")
    private LocalDateTime lastCheckpointAt;

    @Column(name = "schema_version", nullable = false)
    private Integer schemaVersion;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "rebaseline_requested", nullable = false)
    private boolean rebaselineRequested = false;

    @Column(name = "rebuild_requested", nullable = false)
    private boolean rebuildRequested = false;

    /**
     * Create the initial sync state for a site (no changes applied yet).
     *
     * @param siteId site identifier
     * @return new SiteSyncState with all sequences at 0
     */
    public static SiteSyncState initial(UUID siteId) {
        SiteSyncState state = new SiteSyncState();
        state.siteId = siteId;
        state.lastAppliedSeq = 0L;
        state.lastCheckpointSeq = 0L;
        state.schemaVersion = 0;
        state.updatedAt = LocalDateTime.now();
        return state;
    }

    /**
     * Advance the applied watermark after a session commits.
     *
     * @param seq highest sequence now durably applied
     */
    public void advanceWatermark(long seq) {
        this.lastAppliedSeq = seq;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Record the schema version the server currently holds (mirrors {@code site_schemas}).
     *
     * @param version current schema version
     */
    public void recordSchemaVersion(int version) {
        this.schemaVersion = version;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Reset the watermarks for a FULL_SNAPSHOT re-baseline: the snapshot becomes the new baseline, so
     * the applied watermark drops to {@code lastAppliedSeq} (the seq just before the snapshot's first
     * record) and the checkpoint pointer is cleared. The schema version is preserved.
     *
     * @param lastAppliedSeq the seq the snapshot starts from minus one
     */
    public void resetForRebaseline(long lastAppliedSeq) {
        this.lastAppliedSeq = lastAppliedSeq;
        this.lastCheckpointSeq = 0L;
        this.lastCheckpointAt = null;
        this.rebaselineRequested = false;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Flag the site for a full re-baseline: {@code GetSyncState} answers NEED_REBASELINE until the
     * client starts its FULL_SNAPSHOT session (which clears the flag via {@link #resetForRebaseline}).
     */
    public void requestRebaseline() {
        this.rebaselineRequested = true;
    }

    /**
     * Flag the site for a forced out-of-schedule checkpoint rebuild; cleared via
     * {@link #clearRebuildRequested()} once the rebuild completes.
     */
    public void requestRebuild() {
        this.rebuildRequested = true;
    }

    /** Clear the forced-rebuild flag after the rebuild attempt completes. */
    public void clearRebuildRequested() {
        this.rebuildRequested = false;
    }

    /**
     * Record a newly-materialized checkpoint.
     *
     * @param seq sequence the checkpoint represents
     */
    public void recordCheckpoint(long seq) {
        this.lastCheckpointSeq = seq;
        this.lastCheckpointAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PrePersist
    @PreUpdate
    protected void touch() {
        if (lastAppliedSeq == null) lastAppliedSeq = 0L;
        if (lastCheckpointSeq == null) lastCheckpointSeq = 0L;
        if (schemaVersion == null) schemaVersion = 0;
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }
}

package com.bitbi.dfm.delta.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

    /**
     * Width of {@code site_sync_state.last_rebuild_message} (V54). A failure's own text is
     * unbounded — a JDBC or S3 exception can say a great deal — and a value wider than the column
     * throws at flush, which would lose the whole verdict and put the operator back where issue
     * #186 found them, so {@link #recordRebuildOutcome} truncates instead.
     */
    public static final int MAX_REBUILD_MESSAGE_LENGTH = 1000;

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
     * How the most recent <em>finished</em> forced rebuild ended (issue #186). Null until the site
     * has had one. Read together with {@link #rebuildRequested}: while that flag is up, this
     * describes the previous attempt rather than the queued one.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "last_rebuild_outcome", length = 32)
    private CheckpointRebuildOutcome lastRebuildOutcome;

    /** When {@link #lastRebuildOutcome} was recorded. */
    @Column(name = "last_rebuild_outcome_at")
    private LocalDateTime lastRebuildOutcomeAt;

    /**
     * Operator-facing explanation of {@link #lastRebuildOutcome} — the failure's own text, bounded
     * by {@link #MAX_REBUILD_MESSAGE_LENGTH}. Null for {@code COMPLETED}, which has nothing to say.
     */
    @Column(name = "last_rebuild_message", length = MAX_REBUILD_MESSAGE_LENGTH)
    private String lastRebuildMessage;

    /**
     * When {@code GetSyncState} first answered NEED_REBASELINE for the pending request (issue #84).
     * Null while the client has not been told yet — a cancellation up to that point provably
     * reaches it; afterwards the client may already be preparing its snapshot.
     */
    @Column(name = "rebaseline_notified_at")
    private LocalDateTime rebaselineNotifiedAt;

    /**
     * Epoch counter, bumped by a site history wipe and by nothing else (issue #89). The Delta v2
     * client persists the last generation it saw and, on any change, drops its local journal and
     * resets its seq counter to zero — the one thing {@code NEED_REBASELINE} alone cannot express.
     * The row is reset, never deleted, so the counter is monotonic for the life of the site.
     */
    @Column(name = "generation", nullable = false)
    private long generation = 0L;

    /**
     * Epoch of the site's server-side <em>baseline</em>, bumped by everything that discards the
     * site's checkpoints and zeroes {@link #lastCheckpointSeq} — a history wipe and an ordinary
     * FULL_SNAPSHOT re-baseline alike (issue #142). Server-internal and never sent to the client.
     *
     * <p>{@code CheckpointEpochGuard} keys on this rather than on {@link #generation}, because a
     * re-baseline is just as fatal to a checkpoint build in flight and yet must leave the wire epoch
     * alone: bumping {@code generation} would tell the client to drop its journal and reset its seq
     * counter, which a re-baseline never means (035). Monotonic for the life of the site — the row is
     * reset, never deleted — so the two epochs are independent counters and are never compared with
     * each other.</p>
     */
    @Column(name = "baseline_epoch", nullable = false)
    private long baselineEpoch = 0L;

    /**
     * Set by a wipe, consumed by the first checkpoint built afterwards (issue #89). It is what makes
     * the Bit BI baseline recapture automatic — and it is deliberately not consumed at the
     * FULL_SNAPSHOT commit, because at that moment every checkpoint of the site has just been
     * deleted in the same transaction, so a recapture there would freeze baseline 0.
     */
    @Column(name = "wipe_pending", nullable = false)
    private boolean wipePending = false;

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
        state.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
        return state;
    }

    /**
     * Advance the applied watermark after a session commits.
     *
     * @param seq highest sequence now durably applied
     */
    public void advanceWatermark(long seq) {
        this.lastAppliedSeq = seq;
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Record the schema version the server currently holds (mirrors {@code site_schemas}).
     *
     * @param version current schema version
     */
    public void recordSchemaVersion(int version) {
        this.schemaVersion = version;
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Reset the watermarks for a FULL_SNAPSHOT re-baseline: the snapshot becomes the new baseline, so
     * the applied watermark drops to {@code lastAppliedSeq} (the seq just before the snapshot's first
     * record) and the checkpoint pointer is cleared. The schema version is preserved.
     *
     * <p>{@link #baselineEpoch} is incremented and {@link #generation} deliberately is not: the
     * checkpoints this discards are gone as surely as a wipe's, so a build folding them must be
     * refused (issue #142), but the client is not being told to reset anything (035).</p>
     *
     * @param lastAppliedSeq the seq the snapshot starts from minus one
     */
    public void resetForRebaseline(long lastAppliedSeq) {
        this.lastAppliedSeq = lastAppliedSeq;
        this.lastCheckpointSeq = 0L;
        this.lastCheckpointAt = null;
        this.rebaselineRequested = false;
        this.rebaselineNotifiedAt = null;
        this.baselineEpoch++;
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Reset the row for a site history wipe (issue #89): the server keeps nothing of the site's
     * past, so every watermark, the checkpoint pointer and the schema version go back to what a
     * brand-new site has.
     *
     * <p>Three things distinguish it from {@link #resetForRebaseline(long)}. The schema version is
     * zeroed, because {@code site_schemas} is deleted with the rest and the client must re-submit
     * it. {@code rebaselineRequested} is <em>raised</em> rather than cleared, which is what makes
     * {@code GetSyncState} answer NEED_REBASELINE with no new code and gives the wipe the same
     * retry-until-the-snapshot-commits semantics as an ordinary re-baseline. And the
     * {@link #generation} is incremented — the signal that tells the client to reset its own
     * counters, which a re-baseline must never send.</p>
     *
     * <p>{@link #baselineEpoch} moves too, so the guard's epoch is never the weaker of the two: a
     * wipe is a superset of a re-baseline, and a build refused by one must be refused by the other
     * (issue #142).</p>
     */
    public void resetForWipe() {
        this.lastAppliedSeq = 0L;
        this.lastCheckpointSeq = 0L;
        this.lastCheckpointAt = null;
        this.schemaVersion = 0;
        this.rebaselineRequested = true;
        // Re-armed from scratch: this request is new, whatever an earlier one had been told.
        this.rebaselineNotifiedAt = null;
        this.rebuildRequested = false;
        // The verdict travels with the flag (issue #186): a wipe puts the row back to what a
        // brand-new site has, and a verdict about checkpoints it has just deleted describes
        // nothing. A re-baseline leaves both alone, for the same reason in reverse.
        this.lastRebuildOutcome = null;
        this.lastRebuildOutcomeAt = null;
        this.lastRebuildMessage = null;
        this.generation++;
        this.baselineEpoch++;
        this.wipePending = true;
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Take the pending-wipe flag, so the Bit BI baseline recapture runs once per wipe (issue #89).
     *
     * @return {@code true} when this call consumed a pending wipe, {@code false} when none was
     * pending or it was already consumed
     */
    public boolean consumeWipePending() {
        if (!wipePending) {
            return false;
        }
        this.wipePending = false;
        return true;
    }

    /**
     * Flag the site for a full re-baseline: {@code GetSyncState} answers NEED_REBASELINE until the
     * client's FULL_SNAPSHOT session <em>commits</em> (which clears the flag via
     * {@link #resetForRebaseline}) — the flag therefore outlives the whole snapshot upload. Holding
     * it until the commit is deliberate: a snapshot that drops part-way leaves the request standing,
     * so the client retries instead of silently resuming as an ordinary delta on top of a baseline
     * that was never replaced.
     */
    public void requestRebaseline() {
        this.rebaselineRequested = true;
    }

    /**
     * Take back a pending re-baseline request (issue #84): only the flag is cleared, so the
     * watermark, checkpoint pointer and schema version stay exactly as they were and the client
     * resumes ordinary delta from {@code lastAppliedSeq}. {@code updatedAt} is deliberately left
     * alone — like {@link #requestRebaseline()}, raising or dropping a flag is not sync activity.
     * A FULL_SNAPSHOT session already under way is unaffected: it consumes the flag only when it
     * commits ({@link #resetForRebaseline(long)}) and carries its own re-baseline intent.
     *
     * @return {@code true} when a pending request was cleared, {@code false} when none was pending
     */
    public boolean cancelRebaseline() {
        if (!rebaselineRequested) {
            return false;
        }
        this.rebaselineRequested = false;
        this.rebaselineNotifiedAt = null;
        return true;
    }

    /**
     * Remember that {@code GetSyncState} has answered NEED_REBASELINE for the pending request
     * (issue #84), so a later cancellation can say whether it still reaches the client. Stamped
     * once — GetSyncState is polled continuously and re-stamping would be a write per poll.
     *
     * @return {@code true} when this call recorded the notification, {@code false} when it was
     * already recorded or no request is pending
     */
    public boolean markRebaselineNotified() {
        if (!rebaselineRequested || rebaselineNotifiedAt != null) {
            return false;
        }
        this.rebaselineNotifiedAt = LocalDateTime.now(ZoneOffset.UTC);
        return true;
    }

    /**
     * Flag the site for a forced out-of-schedule checkpoint rebuild; cleared via
     * {@link #clearRebuildRequested()} once the rebuild completes.
     */
    public void requestRebuild() {
        this.rebuildRequested = true;
    }

    /**
     * Settle a finished forced rebuild: release the request flag and record what the attempt did
     * (issue #186). One write, deliberately — releasing the flag without saying why is exactly the
     * state this ticket removed, so the two cannot come apart.
     *
     * <p>An ending that keeps the flag (the process shutting down, #162) must <em>not</em> call
     * this: it has not finished, and the next process re-drives it.</p>
     *
     * @param outcome how the attempt ended
     * @param message operator-facing explanation, truncated to {@link #MAX_REBUILD_MESSAGE_LENGTH};
     *                null for an outcome that needs none
     */
    public void recordRebuildOutcome(CheckpointRebuildOutcome outcome, String message) {
        this.rebuildRequested = false;
        this.lastRebuildOutcome = outcome;
        this.lastRebuildOutcomeAt = LocalDateTime.now(ZoneOffset.UTC);
        this.lastRebuildMessage = truncateRebuildMessage(message);
    }

    private static String truncateRebuildMessage(String message) {
        if (message == null || message.length() <= MAX_REBUILD_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_REBUILD_MESSAGE_LENGTH - 1) + "…";
    }

    /**
     * Record a newly-materialized checkpoint.
     *
     * @param seq sequence the checkpoint represents
     */
    public void recordCheckpoint(long seq) {
        this.lastCheckpointSeq = seq;
        this.lastCheckpointAt = LocalDateTime.now(ZoneOffset.UTC);
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    @PrePersist
    @PreUpdate
    protected void touch() {
        if (lastAppliedSeq == null) lastAppliedSeq = 0L;
        if (lastCheckpointSeq == null) lastCheckpointSeq = 0L;
        if (schemaVersion == null) schemaVersion = 0;
        if (updatedAt == null) updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}

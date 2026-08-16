package com.bitbi.dfm.delta.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * A materialized checkpoint of one table's current state (Delta Client v2 — 022).
 *
 * <p>One row per (site, table); records the sequence it represents and row count, plus the key of
 * the materialized Parquet snapshot (attached by a later stage). {@code s3_key_csv} is read-only
 * history since issue #113: builds no longer write a CSV snapshot, but site wipe still deletes the
 * objects earlier builds left behind.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Entity
@Table(name = "checkpoints")
@Getter
@NoArgsConstructor
public class Checkpoint {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "table_name", nullable = false, length = 63)
    private String tableName;

    @Column(name = "seq", nullable = false)
    private Long seq;

    @Column(name = "row_count", nullable = false)
    private Long rowCount;

    @Column(name = "s3_key_csv", length = 1000)
    private String s3KeyCsv;

    @Column(name = "s3_key_parquet", length = 1000)
    private String s3KeyParquet;

    /**
     * Consecutive failed attempts to materialize this table's snapshot (issue #149). Zero while the
     * row has a snapshot; a rematerialize that fails increments it, any success resets it.
     */
    @Column(name = "materialize_attempts", nullable = false)
    private Integer materializeAttempts = 0;

    /** When the attempt counted above last failed; {@code null} while the row has a snapshot. */
    @Column(name = "last_materialize_failure_at")
    private LocalDateTime lastMaterializeFailureAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static Checkpoint create(UUID siteId, String tableName, long seq, long rowCount) {
        Checkpoint checkpoint = new Checkpoint();
        checkpoint.id = UUID.randomUUID();
        checkpoint.siteId = siteId;
        checkpoint.tableName = tableName;
        checkpoint.seq = seq;
        checkpoint.rowCount = rowCount;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        checkpoint.createdAt = now;
        checkpoint.updatedAt = now;
        return checkpoint;
    }

    /** Update the checkpoint to a newer sequence / row count (re-checkpoint). */
    public void update(long seq, long rowCount) {
        this.seq = seq;
        this.rowCount = rowCount;
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public void attachParquet(String s3KeyParquet) {
        this.s3KeyParquet = s3KeyParquet;
        // A snapshot is the only thing the retry was ever after, so reaching one clears the record
        // of getting there. The counter must mean "consecutive failures since the last snapshot" —
        // a running total would eventually retire a table that materializes fine every other week.
        this.materializeAttempts = 0;
        this.lastMaterializeFailureAt = null;
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Record one attempt that ended without a snapshot (issue #149).
     *
     * <p>Called only where the row comes out of the build with no usable key — the population the
     * nightly rematerialize retries. A failure that leaves a still-valid last-good snapshot in
     * place is not counted: nothing is owed for that table.</p>
     */
    public void recordFailedMaterialization() {
        this.materializeAttempts = materializeAttempts() + 1;
        this.lastMaterializeFailureAt = LocalDateTime.now(ZoneOffset.UTC);
        this.updatedAt = this.lastMaterializeFailureAt;
    }

    /**
     * Put a row that had given up back in the nightly retry population (issue #149).
     *
     * <p>The deliberate operator exit from the attempt cap, reached through a forced rebuild. It
     * clears only the bookkeeping — the snapshot key, seq and row count are the build's business.</p>
     */
    public void rearmMaterialization() {
        this.materializeAttempts = 0;
        this.lastMaterializeFailureAt = null;
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Whether this row has spent its attempts and is no longer retried by the nightly pass.
     *
     * <p>Only a row without a snapshot can give up: one that has a key owes nothing, whatever
     * happened on the way to it.</p>
     *
     * @param maxAttempts the configured ceiling ({@code delta.checkpoint.max-materialize-attempts})
     * @return {@code true} when the nightly rematerialize must skip this row
     */
    public boolean hasGivenUpMaterializing(int maxAttempts) {
        return s3KeyParquet == null && materializeAttempts() >= maxAttempts;
    }

    /** Never null in practice; defensive for rows read back before V53's default was applied. */
    public int materializeAttempts() {
        return materializeAttempts == null ? 0 : materializeAttempts;
    }

    /**
     * Detach the snapshot key when a build advanced this row without materializing a new file.
     *
     * <p>The row is reused across builds, so the key of a superseded snapshot would otherwise
     * describe rows older than the {@code seq} beside it — served as if it were current.</p>
     */
    public void detachParquet() {
        this.s3KeyParquet = null;
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now(ZoneOffset.UTC);
        if (updatedAt == null) updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}

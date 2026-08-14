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
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
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

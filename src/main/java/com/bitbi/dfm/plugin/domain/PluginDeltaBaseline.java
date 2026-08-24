package com.bitbi.dfm.plugin.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Per-table SQL baseline for a Delta v2 site (026-bitbi-delta-sql).
 *
 * <p>Captured from the site's current checkpoints at plugin activation/reinit: SQL is emitted
 * only for change records with {@code seq > baselineSeq} of their table. A missing row means
 * baseline 0 — the table's full retained history streams as INSERT SQL (bootstrap without a
 * checkpoint CSV). {@link #suspend()} raises the baseline to {@link Long#MAX_VALUE} after a
 * source-side FULL_SNAPSHOT rebaseline, halting SQL for the table until the user reinits.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Entity
@Table(name = "plugin_delta_baselines")
@Getter
@NoArgsConstructor
public class PluginDeltaBaseline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    @Column(name = "account_plugin_id", nullable = false)
    private Long accountPluginId;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "table_name", nullable = false, length = 63)
    private String tableName;

    @Column(name = "baseline_seq", nullable = false)
    private Long baselineSeq;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static PluginDeltaBaseline create(Long accountPluginId, UUID siteId, String tableName, long baselineSeq) {
        PluginDeltaBaseline baseline = new PluginDeltaBaseline();
        baseline.accountPluginId = accountPluginId;
        baseline.siteId = siteId;
        baseline.tableName = tableName;
        baseline.baselineSeq = baselineSeq;
        baseline.createdAt = LocalDateTime.now(ZoneOffset.UTC);
        baseline.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
        return baseline;
    }

    /**
     * Halt SQL for this table until the plugin is reinitialized (FULL_SNAPSHOT rebaseline).
     */
    public void suspend() {
        this.baselineSeq = Long.MAX_VALUE;
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}

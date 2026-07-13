-- V38: Bit BI SQL generation from Delta v2 changelog segments (026-bitbi-delta-sql).
-- plugin_sql_at IS NULL = the segment awaits plugin SQL generation; the table is the durable
-- work queue (picked FOR UPDATE SKIP LOCKED, per-site head first, so /sql-changes generations
-- are created in seq order per site) — same pattern as the egress queue (V33).

ALTER TABLE changelog_segments ADD COLUMN plugin_sql_at TIMESTAMP;

-- Pre-V38 segments are history: never retro-generate SQL for them. Existing V2 plugin users
-- reinit once after deploy for a consistent baseline (see docs/cr-bitbi-delta-sql.md).
UPDATE changelog_segments SET plugin_sql_at = created_at;

CREATE INDEX idx_changelog_segments_plugin_sql_pending
    ON changelog_segments (site_id, first_seq)
    WHERE plugin_sql_at IS NULL;

-- Per-table plugin baselines: SQL is emitted only for records with seq > baseline_seq(table).
-- Captured from current checkpoints at plugin activation/reinit; missing row = 0 (stream all).
CREATE TABLE plugin_delta_baselines (
    id                BIGSERIAL PRIMARY KEY,
    account_plugin_id BIGINT       NOT NULL,
    site_id           UUID         NOT NULL,
    table_name        VARCHAR(63)  NOT NULL,
    baseline_seq      BIGINT       NOT NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_plugin_delta_baseline UNIQUE (account_plugin_id, site_id, table_name),
    CONSTRAINT fk_plugin_delta_baseline_activation
        FOREIGN KEY (account_plugin_id) REFERENCES account_plugins(id) ON DELETE CASCADE,
    CONSTRAINT fk_plugin_delta_baseline_site
        FOREIGN KEY (site_id) REFERENCES sites(id) ON DELETE CASCADE
);

CREATE INDEX idx_plugin_delta_baseline_site ON plugin_delta_baselines(site_id);

-- Segment seq range of a delta-sourced generation (diagnostics / ordering audits); NULL for V1.
ALTER TABLE plugin_sql_generations ADD COLUMN first_seq BIGINT;
ALTER TABLE plugin_sql_generations ADD COLUMN last_seq  BIGINT;

-- Seed baselines for already-active bit-bi activations from current checkpoints, so existing
-- V2 sites do not re-stream data that is already inside downloaded checkpoint snapshots.
INSERT INTO plugin_delta_baselines (account_plugin_id, site_id, table_name, baseline_seq)
SELECT ap.id, c.site_id, c.table_name, c.seq
FROM account_plugins ap
JOIN sites s ON s.account_id = ap.account_id
JOIN checkpoints c ON c.site_id = s.id
WHERE ap.plugin_id = 'bit-bi' AND ap.is_active = TRUE;

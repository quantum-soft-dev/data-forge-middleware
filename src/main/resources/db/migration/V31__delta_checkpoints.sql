-- V31: Delta Client v2 — materialized checkpoints (feature 022)
-- CR: docs/cr-delta-client-v2.md (§5.1). One current checkpoint per site/table.

CREATE TABLE checkpoints (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id        UUID NOT NULL REFERENCES sites(id) ON DELETE CASCADE,
    table_name     VARCHAR(63) NOT NULL,
    seq            BIGINT NOT NULL,
    row_count      BIGINT NOT NULL,
    s3_key_csv     VARCHAR(1000),
    s3_key_parquet VARCHAR(1000),
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_checkpoint_site_table UNIQUE (site_id, table_name)
);

CREATE INDEX idx_checkpoint_site ON checkpoints(site_id);

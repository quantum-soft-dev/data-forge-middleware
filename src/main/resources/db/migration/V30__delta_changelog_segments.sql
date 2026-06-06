-- V30: Delta Client v2 — append-only changelog segment metadata (feature 022)
-- CR: docs/cr-delta-client-v2.md (§5.1). Segment records themselves live in object storage.

CREATE TABLE changelog_segments (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id       UUID NOT NULL REFERENCES sites(id) ON DELETE CASCADE,
    batch_id      UUID NOT NULL REFERENCES batches(id),
    first_seq     BIGINT NOT NULL,
    last_seq      BIGINT NOT NULL,
    record_count  BIGINT NOT NULL,
    content_hash  VARCHAR(128) NOT NULL,
    s3_key        VARCHAR(1000) NOT NULL,
    mode          VARCHAR(20) NOT NULL,        -- DELTA | FULL_SNAPSHOT
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_segment_site_first_seq UNIQUE (site_id, first_seq)
);

CREATE INDEX idx_segment_site_seq ON changelog_segments(site_id, last_seq);

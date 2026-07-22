-- V29: Delta Client v2 — per-site ingestion watermark + client API version (feature 022)
-- CR: docs/cr-delta-client-v2.md (§5.1)

-- 1. Per-site delta-ingestion watermark + checkpoint pointers (one row per site).
CREATE TABLE site_sync_state (
    site_id             UUID PRIMARY KEY REFERENCES sites(id) ON DELETE CASCADE,
    last_applied_seq    BIGINT NOT NULL DEFAULT 0,
    last_checkpoint_seq BIGINT NOT NULL DEFAULT 0,
    last_checkpoint_at  TIMESTAMP,
    schema_version      INTEGER NOT NULL DEFAULT 0,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2. Client API version per site: V1 = legacy HTTP (/api/dfc), V2 = Delta gRPC.
--    Adding with DEFAULT 'V1' backfills all existing sites to the legacy path;
--    then the default is switched to 'V2' so new sites are Delta v2 by default.
ALTER TABLE sites ADD COLUMN client_api_version VARCHAR(2) NOT NULL DEFAULT 'V1';
ALTER TABLE sites ALTER COLUMN client_api_version SET DEFAULT 'V2';
ALTER TABLE sites ADD CONSTRAINT chk_sites_client_api_version
    CHECK (client_api_version IN ('V1', 'V2'));

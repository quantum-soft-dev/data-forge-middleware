-- V45: Retire the legacy /api/dfc client API (feature 032 / issue #64).
--
-- V1 token issuance has been unreachable since November 2025 and the consumer audit
-- confirmed that every supported client uses Device Flow plus Delta gRPC. Preserve all
-- sites and their history while moving the ingestion-mode marker to its sole valid value.

UPDATE sites
SET client_api_version = 'V2'
WHERE client_api_version = 'V1';

ALTER TABLE sites
    DROP CONSTRAINT IF EXISTS chk_sites_client_api_version;

ALTER TABLE sites
    ALTER COLUMN client_api_version SET DEFAULT 'V2';

ALTER TABLE sites
    ADD CONSTRAINT chk_sites_client_api_version
    CHECK (client_api_version = 'V2');

COMMENT ON COLUMN sites.client_api_version IS
    'Client ingestion API version. V2 (Device Flow + Delta gRPC) is the only supported value.';

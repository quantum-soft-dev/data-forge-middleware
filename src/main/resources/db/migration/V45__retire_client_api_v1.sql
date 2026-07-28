-- V45: Retire the legacy /api/dfc client API (feature 032 / issue #64).
--
-- V1 token issuance has been unreachable since November 2025 and the consumer audit
-- confirmed that every supported client uses Device Flow plus Delta gRPC. Preserve all
-- sites and their history while moving the ingestion-mode marker to its sole valid value.
--
-- During a rolling deployment an old pod can still send V1 explicitly when it creates a site.
-- Normalize that legacy writer value before the single-value CHECK runs. The compatibility
-- trigger can be removed in a later migration after every pre-V45 pod has been drained.

CREATE OR REPLACE FUNCTION normalize_site_client_api_version_v2()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.client_api_version = 'V1' THEN
        NEW.client_api_version := 'V2';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_sites_normalize_client_api_version_v2 ON sites;

CREATE TRIGGER trg_sites_normalize_client_api_version_v2
    BEFORE INSERT OR UPDATE OF client_api_version ON sites
    FOR EACH ROW
    EXECUTE FUNCTION normalize_site_client_api_version_v2();

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
    'Client ingestion API version. V2 (Device Flow + Delta gRPC) is the only stored value; V1 writes are temporarily normalized during rolling deployment.';

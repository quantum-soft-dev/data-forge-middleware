-- Local-dev seed for Delta Client v2 (022) gRPC testing.
-- Creates one account + one V2 site so the Delta gRPC endpoint accepts a minted token.
-- Idempotent: safe to re-run. Apply AFTER the app has booted once (Flyway must have created the schema).
--
--   docker exec -i dfm-postgres psql -U postgres -d dfm < local-dev/seed-delta-site.sql
--
-- Fixed IDs (reused by mint-token.py / smoke-test.sh):
--   ACCOUNT_ID = 0de17a00-0000-4000-8000-000000000001
--   SITE_ID    = 0de17a00-0000-4000-8000-0000000000a1
--   domain     = delta-store.test
--   client_secret plaintext = admin-site-secret  (BCrypt hash reused from test fixtures)

INSERT INTO accounts (id, email, name, is_active, created_at, updated_at, identity_provider_user_id)
VALUES ('0de17a00-0000-4000-8000-000000000001', 'delta-test@example.com', 'Delta v2 Test Account',
        true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'auth0|deltatest000001')
ON CONFLICT (id) DO UPDATE SET is_active = true, updated_at = CURRENT_TIMESTAMP;

INSERT INTO sites (id, account_id, domain, client_secret_hash, display_name, is_active,
                   created_at, updated_at, site_name, client_api_version)
VALUES ('0de17a00-0000-4000-8000-0000000000a1', '0de17a00-0000-4000-8000-000000000001',
        'delta-store.test', '$2a$10$zOS1.KWMj6b2crXsM1hsh.hssSJghfUH1Wdxx3RMQzzNfK5zzPhBK',
        'Delta Store (v2)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'delta-store', 'V2')
ON CONFLICT (id) DO UPDATE SET is_active = true, client_api_version = 'V2', updated_at = CURRENT_TIMESTAMP;

SELECT s.id AS site_id, s.account_id, s.domain, s.client_api_version, s.is_active
FROM sites s WHERE s.id = '0de17a00-0000-4000-8000-0000000000a1';

-- Test Data Setup for Contract and Integration Tests
-- IMPORTANT: Uses exact UUIDs from AdminContractTest
-- - MOCK_ACCOUNT_ID = a1b2c3d4-e5f6-7890-abcd-ef1234567890
-- - MOCK_SITE_ID = b2c3d4e5-f6a7-8901-bcde-f12345678901
-- - MOCK_BATCH_ID = c3d4e5f6-a7b8-9012-cdef-123456789012

-- Create missing partitions for test data (if not exists)
CREATE TABLE IF NOT EXISTS error_logs_2025_09 PARTITION OF error_logs
    FOR VALUES FROM ('2025-09-01') TO ('2025-10-01');

CREATE TABLE IF NOT EXISTS error_logs_2025_10 PARTITION OF error_logs
    FOR VALUES FROM ('2025-10-01') TO ('2025-11-01');

-- Clean up (idempotent) - Order matters due to foreign key constraints
-- Delete child tables first, then parent tables.
--
-- Owned accounts / sites (issue #228). The original predicates were
-- accounts.email LIKE '%@example.com' and sites.domain LIKE '%.example.com'. Three more sets
-- have to travel with them, and a general "sweep every non-cascading FK by its own relationship"
-- does not cover (3):
--   (1) batches.account_id / sites.account_id (V3 / V2, no cascade) -- Batch.start takes the two
--       ids independently, so a batch pairing an owned account with a foreign-domain site
--       survives a site-keyed DELETE FROM batches and blocks DELETE FROM accounts.
--   (2) device_authorizations.site_id / .account_id (V21, no cascade) -- the fixture had no
--       statement for that table, so an approved row pointing at a seeded site blocks
--       DELETE FROM sites.
--   (3) Rows outside the seed identity predicates: *.test.local (three integration classes)
--       and {uuid}_example.com (BatchRetentionIntegrationTest; LIKE '%.example.com' needs a
--       literal dot). Widening DELETE FROM sites pulls those sites in, so every site-keyed
--       statement above it (error_logs, checkpoints, site_sync_state, the segment sweep's
--       site_id arm) has to use the same set or it blocks on the way through.
-- Owned accounts: the two email predicates. Owned sites: the two domain predicates PLUS every
-- site of an owned account. Owned batches: site in owned sites OR account in owned accounts.
-- ScriptUtils splits on ';' and does not understand a DO $$ block, so the subqueries are
-- repeated rather than factored into a temp table or a procedure.

DELETE FROM comparison_results WHERE comparison_id IN (SELECT id FROM file_comparisons WHERE account_id IN (SELECT id FROM accounts WHERE email LIKE '%@example.com' OR email LIKE '%@test.local'));
DELETE FROM file_comparisons WHERE account_id IN (SELECT id FROM accounts WHERE email LIKE '%@example.com' OR email LIKE '%@test.local');
DELETE FROM admin_action_logs WHERE target_account_id IN (SELECT id FROM accounts WHERE email LIKE '%@example.com' OR email LIKE '%@test.local') OR admin_account_id IN (SELECT id FROM accounts WHERE email LIKE '%@example.com' OR email LIKE '%@test.local');
-- error_logs.site_id has no ON DELETE action (V5). Must widen with owned sites, not just
-- %.example.com, or a pulled-in {uuid}_example.com site blocks DELETE FROM sites.
DELETE FROM error_logs WHERE site_id IN (
    SELECT id FROM sites
     WHERE domain LIKE '%.example.com'
        OR domain LIKE '%.test.local'
        OR account_id IN (SELECT id FROM accounts WHERE email LIKE '%@example.com' OR email LIKE '%@test.local')
);
DELETE FROM uploaded_files WHERE batch_id IN (
    SELECT id FROM batches
     WHERE site_id IN (
            SELECT id FROM sites
             WHERE domain LIKE '%.example.com'
                OR domain LIKE '%.test.local'
                OR account_id IN (SELECT id FROM accounts WHERE email LIKE '%@example.com' OR email LIKE '%@test.local')
          )
        OR account_id IN (SELECT id FROM accounts WHERE email LIKE '%@example.com' OR email LIKE '%@test.local')
);
-- account_plugins may reference batches via baseline_batch_id (FK RESTRICT), so must be deleted
-- before batches -- and by that relationship as well as by account (issue #226): the constraint is
-- fk_account_plugins_baseline_batch, which RESTRICTs on the *batch*, so an activation owned by an
-- account this predicate does not match still blocks the DELETE FROM batches below. Same reasoning
-- as the changelog_segments sweep further down; these two are the only FKs to batches without a
-- cascade.
-- Note the ownership this widens: the second predicate deletes activations of accounts the fixture
-- does not otherwise own, whenever their baseline_batch_id points at a batch it is about to delete,
-- and plugin_sql_generations / plugin_delta_baselines / download_links cascade with them. That is
-- intended -- the alternative is the FK stopping the run -- but a test seeding an activation for a
-- foreign account against a seeded batch will lose it mid-class, with nothing pointing here.
DELETE FROM account_plugins WHERE account_id IN (SELECT id FROM accounts WHERE email LIKE '%@example.com' OR email LIKE '%@test.local')
                               OR baseline_batch_id IN (
                                    SELECT id FROM batches
                                     WHERE site_id IN (
                                            SELECT id FROM sites
                                             WHERE domain LIKE '%.example.com'
                                                OR domain LIKE '%.test.local'
                                                OR account_id IN (SELECT id FROM accounts WHERE email LIKE '%@example.com' OR email LIKE '%@test.local')
                                          )
                                        OR account_id IN (SELECT id FROM accounts WHERE email LIKE '%@example.com' OR email LIKE '%@test.local')
                               );
-- Delta v2 (022): changelog_segments references batches (no cascade), so clear before batches.
-- Both relationships are cleared, not just site_id (issue #226): the blocking constraint is
-- changelog_segments_batch_id_fkey, and a segment's batch need not belong to the segment's site --
-- ChangelogSegment.create(siteId, batchId, ...) takes the two independently, so a test can pair a
-- site this predicate does not match with a batch the next statement deletes. Same shape as the
-- uploaded_files sweep above, and for the same constraint-shaped reason.
-- The site_id arm is the owned-sites set, not just %.example.com, so a pulled-in foreign-domain
-- site does not leave a segment to block DELETE FROM sites via ON DELETE CASCADE waiting on
-- nothing -- the cascade is there, but the batch_id arm still has to name every batch we will
-- delete, including those reached only through account_id.
DELETE FROM changelog_segments WHERE site_id IN (
                                        SELECT id FROM sites
                                         WHERE domain LIKE '%.example.com'
                                            OR domain LIKE '%.test.local'
                                            OR account_id IN (SELECT id FROM accounts WHERE email LIKE '%@example.com' OR email LIKE '%@test.local')
                                    )
                                  OR batch_id IN (
                                        SELECT id FROM batches
                                         WHERE site_id IN (
                                                SELECT id FROM sites
                                                 WHERE domain LIKE '%.example.com'
                                                    OR domain LIKE '%.test.local'
                                                    OR account_id IN (SELECT id FROM accounts WHERE email LIKE '%@example.com' OR email LIKE '%@test.local')
                                              )
                                            OR account_id IN (SELECT id FROM accounts WHERE email LIKE '%@example.com' OR email LIKE '%@test.local')
                                  );
DELETE FROM checkpoints WHERE site_id IN (
    SELECT id FROM sites
     WHERE domain LIKE '%.example.com'
        OR domain LIKE '%.test.local'
        OR account_id IN (SELECT id FROM accounts WHERE email LIKE '%@example.com' OR email LIKE '%@test.local')
);
DELETE FROM site_sync_state WHERE site_id IN (
    SELECT id FROM sites
     WHERE domain LIKE '%.example.com'
        OR domain LIKE '%.test.local'
        OR account_id IN (SELECT id FROM accounts WHERE email LIKE '%@example.com' OR email LIKE '%@test.local')
);
-- device_authorizations.site_id / .account_id have no ON DELETE action (V21). An approved
-- leftover pointing at a seeded site is live today (DeviceFlowSessionSupersedeContractTest
-- used to hand-delete its own rows to keep the next @Sql from failing).
DELETE FROM device_authorizations WHERE site_id IN (
                                        SELECT id FROM sites
                                         WHERE domain LIKE '%.example.com'
                                            OR domain LIKE '%.test.local'
                                            OR account_id IN (SELECT id FROM accounts WHERE email LIKE '%@example.com' OR email LIKE '%@test.local')
                                    )
                                  OR account_id IN (SELECT id FROM accounts WHERE email LIKE '%@example.com' OR email LIKE '%@test.local');
DELETE FROM batches WHERE site_id IN (
                            SELECT id FROM sites
                             WHERE domain LIKE '%.example.com'
                                OR domain LIKE '%.test.local'
                                OR account_id IN (SELECT id FROM accounts WHERE email LIKE '%@example.com' OR email LIKE '%@test.local')
                        )
                       OR account_id IN (SELECT id FROM accounts WHERE email LIKE '%@example.com' OR email LIKE '%@test.local');
DELETE FROM sites WHERE domain LIKE '%.example.com'
                     OR domain LIKE '%.test.local'
                     OR account_id IN (SELECT id FROM accounts WHERE email LIKE '%@example.com' OR email LIKE '%@test.local');
-- Clean up plugin-related data (FK to accounts or referencing account)
-- Note: plugin_audit_logs cleanup skipped - table is partitioned and has no FK to accounts
DELETE FROM accounts WHERE email LIKE '%@example.com' OR email LIKE '%@test.local';

-- Test accounts
-- NOTE: identity_provider_user_id must follow Auth0 format: {provider}|{alphanumeric} (e.g., 'auth0|abc123')
INSERT INTO accounts (id, email, name, is_active, created_at, updated_at, identity_provider_user_id)
VALUES ('a1b2c3d4-e5f6-7890-abcd-ef1234567890', 'admin-test@example.com', 'Admin Test Account', true, '2025-09-06 00:00:00', CURRENT_TIMESTAMP AT TIME ZONE 'UTC', 'auth0|admintest123456');

INSERT INTO accounts (id, email, name, is_active, created_at, updated_at, identity_provider_user_id)
VALUES ('0199bab1-fad2-bf76-c478-eae1f61e1c17', 'test-account-2@example.com', 'Test Account 2', true, '2025-09-16 00:00:00', CURRENT_TIMESTAMP AT TIME ZONE 'UTC', 'auth0|60f7b8a8b4a0f10074c5d0e1');

INSERT INTO accounts (id, email, name, is_active, created_at, updated_at)
VALUES ('0199bab2-3cbd-cc95-a989-57ba51d258c8', 'inactive@example.com', 'Inactive Account', false, '2025-09-26 00:00:00', CURRENT_TIMESTAMP AT TIME ZONE 'UTC');

-- Plugin system test account (used by PluginActivationIntegrationTest, PluginAuditIntegrationTest, etc.)
INSERT INTO accounts (id, email, name, is_active, created_at, updated_at, identity_provider_user_id)
VALUES ('0199baac-f851-7ed9-5963-00dbaf07b233', 'plugin-test@example.com', 'Plugin Test Account', true, '2025-12-01 00:00:00', CURRENT_TIMESTAMP AT TIME ZONE 'UTC', 'auth0|plugintest123456');

-- Test sites (with BCrypt hashed client_secret_hash)
-- NOTE: After migration V7, column renamed from client_secret to client_secret_hash
INSERT INTO sites (id, account_id, domain, client_secret_hash, display_name, is_active, created_at, updated_at, site_name, client_api_version)
VALUES ('b2c3d4e5-f6a7-8901-bcde-f12345678901', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890', 'admin-site.example.com', '$2a$10$zOS1.KWMj6b2crXsM1hsh.hssSJghfUH1Wdxx3RMQzzNfK5zzPhBK', 'Admin Test Site', true, '2025-09-11 00:00:00', CURRENT_TIMESTAMP AT TIME ZONE 'UTC', 'admin-site.example.com', 'V2');
-- Plaintext: admin-site-secret

INSERT INTO sites (id, account_id, domain, client_secret_hash, display_name, is_active, created_at, updated_at, site_name, client_api_version)
VALUES ('0199baac-f852-753f-6fc3-7c994fc38654', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890', 'store-01.example.com', '$2a$10$w4ybRJqsZPH4IWHXHaN1rukDAfZc6Ri4P45Hpk3mlfbZpHIHYYyBm', 'Store 01', true, '2025-09-21 00:00:00', CURRENT_TIMESTAMP AT TIME ZONE 'UTC', 'store-01.example.com', 'V2');
-- Plaintext: valid-secret-uuid

INSERT INTO sites (id, account_id, domain, client_secret_hash, display_name, is_active, created_at, updated_at, site_name, client_api_version)
VALUES ('0199baaf-ea7a-bd1f-6f6c-8610b9ddc4d7', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890', 'store-02.example.com', '$2a$10$R2zm98c/.YXxfrR3dDvj6uYfGv7ITs7cyqpWwpImC1n/tTq20bQqG', 'Store 02', true, '2025-09-26 00:00:00', CURRENT_TIMESTAMP AT TIME ZONE 'UTC', 'store-02.example.com', 'V2');
-- Plaintext: inactive-secret-uuid

INSERT INTO sites (id, account_id, domain, client_secret_hash, display_name, is_active, created_at, updated_at, site_name, client_api_version)
VALUES ('0199bab0-ca3b-e41c-5521-2f4b33fda8b6', '0199bab1-fad2-bf76-c478-eae1f61e1c17', 'store-03.example.com', '$2a$10$8KGp8l7VXbUwby9yQACEEuOYuBApd8uWzSy4hGppksFbIC07MdnB2', 'Store 03', true, '2025-10-01 00:00:00', CURRENT_TIMESTAMP AT TIME ZONE 'UTC', 'store-03.example.com', 'V2');
-- Plaintext: batch-test-secret

-- Sites for AuthenticationIntegrationTest
INSERT INTO sites (id, account_id, domain, client_secret_hash, display_name, is_active, created_at, updated_at, site_name, client_api_version)
VALUES ('0199bab0-1111-1111-1111-111111111111', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890', 'test-store.example.com', '$2a$10$gMwFxteMaHb0kkSu3vaK7.z1PD7tXwSxtwZz.Ib.tzITdaFg.nbRy', 'Test Store', true, '2025-10-01 00:00:00', CURRENT_TIMESTAMP AT TIME ZONE 'UTC', 'test-store.example.com', 'V2');
-- Plaintext: test-client-secret-uuid

INSERT INTO sites (id, account_id, domain, client_secret_hash, display_name, is_active, created_at, updated_at, site_name, client_api_version)
VALUES ('0199bab0-2222-2222-2222-222222222222', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890', 'inactive-store.example.com', '$2a$10$H.wcapH9pLI0XcM6Sz1EHeECA2axttsYPjE90GObxmOEkDvgYDEgi', 'Inactive Store', false, '2025-10-01 00:00:00', CURRENT_TIMESTAMP AT TIME ZONE 'UTC', 'inactive-store.example.com', 'V2');
-- Plaintext: inactive-secret

INSERT INTO sites (id, account_id, domain, client_secret_hash, display_name, is_active, created_at, updated_at, site_name, client_api_version)
VALUES ('0199bab0-3333-3333-3333-333333333333', '0199bab2-3cbd-cc95-a989-57ba51d258c8', 'orphaned-store.example.com', '$2a$10$t.QXSUqKLALkr4yq6F5PLuX78.Zl1WDYCKF.ZAXW3oym4XNxMv8aO', 'Orphaned Store (inactive parent)', true, '2025-10-01 00:00:00', CURRENT_TIMESTAMP AT TIME ZONE 'UTC', 'orphaned-store.example.com', 'V2');
-- Plaintext: orphaned-secret

-- Test batches
-- MOCK_BATCH_ID - COMPLETED to allow new batches to start for the same site
-- Updated 2025-11-03: Changed uploaded_files_count from 0 to 2 to support new business rule (batches must have files)
INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count, total_size, has_errors, started_at, created_at, completed_at)
VALUES ('a1b2c3d4-e5f6-7890-abcd-ef1234567890', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890', '0199baac-f852-753f-6fc3-7c994fc38654', 'COMPLETED', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890/store-01.example.com/2025-10-06/10-00/', 2, 2048, false, '2025-10-05 10:00:00', '2025-10-05 10:00:00', '2025-10-05 10:30:00');

INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count, total_size, has_errors, started_at, created_at, completed_at)
VALUES ('c3d4e5f6-a7b8-9012-cdef-123456789012', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890', 'b2c3d4e5-f6a7-8901-bcde-f12345678901', 'COMPLETED', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890/admin-site.example.com/2025-10-05/14-30/', 2, 3072, false, '2025-10-05 14:30:00', '2025-10-05 14:30:00', '2025-10-05 15:30:00');

-- IN_PROGRESS_BATCH_ID for Device Batch Controller tests (TC15-TC20)
-- Used for complete/fail/cancel/get operations
INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count, total_size, has_errors, started_at, created_at, completed_at)
VALUES ('b1c2d3e4-f5a6-7890-bcde-f12345678903', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890', '0199baac-f852-753f-6fc3-7c994fc38654', 'IN_PROGRESS', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890/store-01.example.com/2025-10-06/12-00/', 1, 1024, false, CURRENT_TIMESTAMP AT TIME ZONE 'UTC', CURRENT_TIMESTAMP AT TIME ZONE 'UTC', NULL);

-- IN_PROGRESS batch for store-02 (same account, different site) - used for TC17 cross-site authorization test
INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count, total_size, has_errors, started_at, created_at, completed_at)
VALUES ('b1c2d3e4-f5a6-7890-bcde-f12345678905', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890', '0199baaf-ea7a-bd1f-6f6c-8610b9ddc4d7', 'IN_PROGRESS', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890/store-02.example.com/2025-10-06/13-00/', 1, 1024, false, CURRENT_TIMESTAMP AT TIME ZONE 'UTC', CURRENT_TIMESTAMP AT TIME ZONE 'UTC', NULL);

INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count, total_size, has_errors, started_at, created_at, completed_at)
VALUES ('0199bab2-ca1c-3d0e-441d-adb776a62579', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890', '0199baac-f852-753f-6fc3-7c994fc38654', 'FAILED', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890/store-01.example.com/2025-10-04/10-00/', 3, 3072, true, '2025-10-04 10:00:00', '2025-10-04 10:00:00', '2025-10-04 10:30:00');

-- Batch for store-03 (different account) - used for cross-tenant authorization tests
-- COMPLETED status so it doesn't interfere with batch lifecycle tests that create new batches for store-03
INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count, total_size, has_errors, started_at, created_at, completed_at)
VALUES ('0199bab2-dddd-dddd-dddd-dddddddddddd', '0199bab1-fad2-bf76-c478-eae1f61e1c17', '0199bab0-ca3b-e41c-5521-2f4b33fda8b6', 'COMPLETED', '0199bab1-fad2-bf76-c478-eae1f61e1c17/store-03.example.com/2025-10-06/13-00/', 1, 2048, false, '2025-10-06 13:00:00', '2025-10-06 13:00:00', '2025-10-06 13:30:00');

-- Test uploaded files
INSERT INTO uploaded_files (id, batch_id, original_file_name, s3_key, file_size, content_type, checksum, uploaded_at)
VALUES ('0199bab3-0429-c04f-9482-7f3b88456918', 'c3d4e5f6-a7b8-9012-cdef-123456789012', 'data1.csv', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890/admin-site.example.com/2025-10-05/14-30/data1.csv', 1024, 'text/csv', 'checksum1', '2025-10-05 14:35:00');

INSERT INTO uploaded_files (id, batch_id, original_file_name, s3_key, file_size, content_type, checksum, uploaded_at)
VALUES ('0199bab3-69d1-d291-0fb6-c8dd6d09ee88', 'c3d4e5f6-a7b8-9012-cdef-123456789012', 'data2.csv', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890/admin-site.example.com/2025-10-05/14-30/data2.csv', 2048, 'text/csv', 'checksum2', '2025-10-05 14:36:00');

-- Files for MOCK_BATCH_ID (added 2025-11-03 to support new business rule: batches must have files)
INSERT INTO uploaded_files (id, batch_id, original_file_name, s3_key, file_size, content_type, checksum, uploaded_at)
VALUES ('a1b2c3d4-e5f6-7890-abcd-111111111111', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890', 'mock-file1.csv', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890/store-01.example.com/2025-10-06/10-00/mock-file1.csv', 1024, 'text/csv', 'mock-checksum1', '2025-10-05 10:05:00');

INSERT INTO uploaded_files (id, batch_id, original_file_name, s3_key, file_size, content_type, checksum, uploaded_at)
VALUES ('a1b2c3d4-e5f6-7890-abcd-222222222222', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890', 'mock-file2.csv', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890/store-01.example.com/2025-10-06/10-00/mock-file2.csv', 1024, 'text/csv', 'mock-checksum2', '2025-10-05 10:06:00');

-- File for IN_PROGRESS batch (TC15-TC20)
INSERT INTO uploaded_files (id, batch_id, original_file_name, s3_key, file_size, content_type, checksum, uploaded_at)
VALUES ('0199bab3-a134-e3e5-e76e-7ba0a7c44fa5', 'b1c2d3e4-f5a6-7890-bcde-f12345678903', 'existing-file.csv', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890/store-01.example.com/2025-10-06/12-00/existing-file.csv', 1024, 'text/csv', 'abc123def456', CURRENT_TIMESTAMP AT TIME ZONE 'UTC');

-- File for store-02 IN_PROGRESS batch (TC17)
INSERT INTO uploaded_files (id, batch_id, original_file_name, s3_key, file_size, content_type, checksum, uploaded_at)
VALUES ('b1c2d3e4-aaaa-bbbb-cccc-dddddddddddd', 'b1c2d3e4-f5a6-7890-bcde-f12345678905', 'store-02-file.csv', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890/store-02.example.com/2025-10-06/13-00/store-02-file.csv', 1024, 'text/csv', 'store02checksum', CURRENT_TIMESTAMP AT TIME ZONE 'UTC');

-- File for store-03 batch (used for cross-tenant authorization tests)
INSERT INTO uploaded_files (id, batch_id, original_file_name, s3_key, file_size, content_type, checksum, uploaded_at)
VALUES ('0199bab3-eeee-eeee-eeee-eeeeeeeeeeee', '0199bab2-dddd-dddd-dddd-dddddddddddd', 'store-03-file.csv', '0199bab1-fad2-bf76-c478-eae1f61e1c17/store-03.example.com/2025-10-06/13-00/store-03-file.csv', 2048, 'text/csv', 'xyz789abc123', CURRENT_TIMESTAMP AT TIME ZONE 'UTC');

-- Test error logs
INSERT INTO error_logs (id, batch_id, site_id, type, title, message, metadata, occurred_at)
VALUES ('0199bab3-d4d6-c1d1-226a-241c7b874314', '0199bab2-ca1c-3d0e-441d-adb776a62579', '0199baac-f852-753f-6fc3-7c994fc38654', 'FileReadError', 'Error Title', 'Failed to read file', '{"filename": "corrupted.csv", "line": 42}'::jsonb, '2025-10-04 10:15:00');

INSERT INTO error_logs (id, batch_id, site_id, type, title, message, metadata, occurred_at)
VALUES ('0199bab4-0e14-2b25-9928-d18ce5f2d66d', '0199bab2-ca1c-3d0e-441d-adb776a62579', '0199baac-f852-753f-6fc3-7c994fc38654', 'ValidationError', 'Error Title 2','Invalid data format', '{"filename": "invalid.csv", "column": "amount"}'::jsonb, '2025-10-04 10:20:00');

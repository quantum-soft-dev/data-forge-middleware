-- Test Data Setup for Contract and Integration Tests
-- IMPORTANT: Uses exact UUIDs from AdminContractTest
-- - MOCK_ACCOUNT_ID = a1b2c3d4-e5f6-7890-abcd-ef1234567890
-- - MOCK_SITE_ID = b2c3d4e5-f6a7-8901-bcde-f12345678901
-- - MOCK_BATCH_ID = c3d4e5f6-a7b8-9012-cdef-123456789012

-- Clean up (idempotent)
DELETE FROM error_logs WHERE site_id IN (SELECT id FROM sites WHERE domain LIKE '%.example.com');
DELETE FROM uploaded_files WHERE batch_id IN (SELECT id FROM batches WHERE site_id IN (SELECT id FROM sites WHERE domain LIKE '%.example.com'));
DELETE FROM batches WHERE site_id IN (SELECT id FROM sites WHERE domain LIKE '%.example.com');
DELETE FROM sites WHERE domain LIKE '%.example.com';
DELETE FROM accounts WHERE email LIKE '%@example.com';

-- Test accounts
INSERT INTO accounts (id, email, name, is_active, created_at, updated_at)
VALUES ('a1b2c3d4-e5f6-7890-abcd-ef1234567890', 'admin-test@example.com', 'Admin Test Account', true, '2025-09-06 00:00:00', CURRENT_TIMESTAMP);

INSERT INTO accounts (id, email, name, is_active, created_at, updated_at)
VALUES ('0199bab1-fad2-bf76-c478-eae1f61e1c17', 'test-account-2@example.com', 'Test Account 2', true, '2025-09-16 00:00:00', CURRENT_TIMESTAMP);

INSERT INTO accounts (id, email, name, is_active, created_at, updated_at)
VALUES ('0199bab2-3cbd-cc95-a989-57ba51d258c8', 'inactive@example.com', 'Inactive Account', false, '2025-09-26 00:00:00', CURRENT_TIMESTAMP);

-- Test sites
INSERT INTO sites (id, account_id, domain, client_secret, display_name, is_active, created_at, updated_at)
VALUES ('b2c3d4e5-f6a7-8901-bcde-f12345678901', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890', 'admin-site.example.com', 'admin-site-secret', 'Admin Test Site', true, '2025-09-11 00:00:00', CURRENT_TIMESTAMP);

INSERT INTO sites (id, account_id, domain, client_secret, display_name, is_active, created_at, updated_at)
VALUES ('0199baac-f852-753f-6fc3-7c994fc38654', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890', 'store-01.example.com', 'valid-secret-uuid', 'Store 01', true, '2025-09-21 00:00:00', CURRENT_TIMESTAMP);

INSERT INTO sites (id, account_id, domain, client_secret, display_name, is_active, created_at, updated_at)
VALUES ('0199baaf-ea7a-bd1f-6f6c-8610b9ddc4d7', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890', 'store-02.example.com', 'inactive-secret-uuid', 'Store 02 (Inactive)', false, '2025-09-26 00:00:00', CURRENT_TIMESTAMP);

INSERT INTO sites (id, account_id, domain, client_secret, display_name, is_active, created_at, updated_at)
VALUES ('0199bab0-ca3b-e41c-5521-2f4b33fda8b6', '0199bab1-fad2-bf76-c478-eae1f61e1c17', 'store-03.example.com', 'batch-test-secret', 'Store 03', true, '2025-10-01 00:00:00', CURRENT_TIMESTAMP);

-- Test batches
INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count, total_size, has_errors, started_at, created_at, completed_at)
VALUES ('c3d4e5f6-a7b8-9012-cdef-123456789012', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890', 'b2c3d4e5-f6a7-8901-bcde-f12345678901', 'COMPLETED', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890/admin-site.example.com/2025-10-05/14-30/', 5, 5120, false, '2025-10-05 14:30:00', '2025-10-05 14:30:00', '2025-10-05 15:30:00');

INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count, total_size, has_errors, started_at, created_at, completed_at)
VALUES ('0199bab2-8d63-8563-8340-edbf1c11c778', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890', '0199baac-f852-753f-6fc3-7c994fc38654', 'IN_PROGRESS', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890/store-01.example.com/2025-10-06/12-00/', 1, 1024, false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL);

INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count, total_size, has_errors, started_at, created_at, completed_at)
VALUES ('0199bab2-ca1c-3d0e-441d-adb776a62579', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890', '0199baac-f852-753f-6fc3-7c994fc38654', 'FAILED', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890/store-01.example.com/2025-10-04/10-00/', 3, 3072, true, '2025-10-04 10:00:00', '2025-10-04 10:00:00', '2025-10-04 10:30:00');

-- Test uploaded files
INSERT INTO uploaded_files (id, batch_id, original_file_name, s3_key, file_size, content_type, checksum, uploaded_at)
VALUES ('0199bab3-0429-c04f-9482-7f3b88456918', 'c3d4e5f6-a7b8-9012-cdef-123456789012', 'data1.csv', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890/admin-site.example.com/2025-10-05/14-30/data1.csv', 1024, 'text/csv', 'checksum1', '2025-10-05 14:35:00');

INSERT INTO uploaded_files (id, batch_id, original_file_name, s3_key, file_size, content_type, checksum, uploaded_at)
VALUES ('0199bab3-69d1-d291-0fb6-c8dd6d09ee88', 'c3d4e5f6-a7b8-9012-cdef-123456789012', 'data2.csv', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890/admin-site.example.com/2025-10-05/14-30/data2.csv', 2048, 'text/csv', 'checksum2', '2025-10-05 14:36:00');

INSERT INTO uploaded_files (id, batch_id, original_file_name, s3_key, file_size, content_type, checksum, uploaded_at)
VALUES ('0199bab3-a134-e3e5-e76e-7ba0a7c44fa5', '0199bab2-8d63-8563-8340-edbf1c11c778', 'existing-file.csv', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890/store-01.example.com/2025-10-06/12-00/existing-file.csv', 1024, 'text/csv', 'abc123def456', CURRENT_TIMESTAMP);

-- Test error logs
INSERT INTO error_logs (id, batch_id, site_id, type, title, message, metadata, occurred_at)
VALUES ('0199bab3-d4d6-c1d1-226a-241c7b874314', '0199bab2-ca1c-3d0e-441d-adb776a62579', '0199baac-f852-753f-6fc3-7c994fc38654', 'FileReadError', 'Error Title', 'Failed to read file', '{"filename": "corrupted.csv", "line": 42}'::jsonb, '2025-10-04 10:15:00');

INSERT INTO error_logs (id, batch_id, site_id, type, title, message, metadata, occurred_at)
VALUES ('0199bab4-0e14-2b25-9928-d18ce5f2d66d', '0199bab2-ca1c-3d0e-441d-adb776a62579', '0199baac-f852-753f-6fc3-7c994fc38654', 'ValidationError', 'Error Title 2','Invalid data format', '{"filename": "invalid.csv", "column": "amount"}'::jsonb, '2025-10-04 10:20:00');

-- Test Data Setup for Contract and Integration Tests
-- Author: Data Forge Team
-- Date: 2025-10-06

-- Clean up existing test data
DELETE FROM error_logs WHERE site_id IN (SELECT id FROM sites WHERE domain LIKE '%.example.com');
DELETE FROM uploaded_files WHERE batch_id IN (SELECT id FROM batches WHERE site_id IN (SELECT id FROM sites WHERE domain LIKE '%.example.com'));
DELETE FROM batches WHERE site_id IN (SELECT id FROM sites WHERE domain LIKE '%.example.com');
DELETE FROM sites WHERE domain LIKE '%.example.com';
DELETE FROM accounts WHERE email LIKE '%@example.com';

-- Create test accounts
INSERT INTO accounts (id, email, name, is_active, created_at, updated_at)
VALUES
    ('a0000000-0000-0000-0000-000000000001', 'test-account-1@example.com', 'Test Account 1', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('a0000000-0000-0000-0000-000000000002', 'test-account-2@example.com', 'Test Account 2', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('a0000000-0000-0000-0000-000000000003', 'inactive@example.com', 'Inactive Account', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Create test sites
INSERT INTO sites (id, account_id, domain, client_secret, display_name, is_active, created_at, updated_at)
VALUES
    -- Active site for authentication tests
    ('s0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'store-01.example.com',
     'valid-secret-uuid', 'Store 01', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

    -- Inactive site for authentication tests
    ('s0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000001', 'store-02.example.com',
     'inactive-secret-uuid', 'Store 02 (Inactive)', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

    -- Additional active site for batch tests
    ('s0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000002', 'store-03.example.com',
     'batch-test-secret', 'Store 03', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Create test batch for duplicate upload tests
INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count, total_size, has_errors, started_at, created_at)
VALUES
    ('b0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 's0000000-0000-0000-0000-000000000001',
     'IN_PROGRESS', 'a0000000-0000-0000-0000-000000000001/store-01.example.com/2025-10-06/12-00/',
     1, 1024, false, CURRENT_TIMESTAMP - INTERVAL '10 minutes', CURRENT_TIMESTAMP - INTERVAL '10 minutes');

-- Create test uploaded file for duplicate filename tests
INSERT INTO uploaded_files (id, batch_id, original_file_name, s3_key, file_size, content_type, checksum, uploaded_at)
VALUES
    ('f0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000001',
     'existing-file.csv', 'a0000000-0000-0000-0000-000000000001/store-01.example.com/2025-10-06/12-00/existing-file.csv',
     1024, 'text/csv', 'abc123def456', CURRENT_TIMESTAMP - INTERVAL '5 minutes');

-- Verify test data
SELECT 'Accounts created: ' || COUNT(*) FROM accounts WHERE email LIKE '%@example.com';
SELECT 'Sites created: ' || COUNT(*) FROM sites WHERE domain LIKE '%.example.com';
SELECT 'Batches created: ' || COUNT(*) FROM batches WHERE site_id IN (SELECT id FROM sites WHERE domain LIKE '%.example.com');

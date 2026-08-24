-- Delta Client v2 site fixture for HTTP retirement/drain tests.
-- Applied AFTER test-data.sql (which cleans up all %.example.com sites/batches).
-- The removed HTTP write endpoints must stay unavailable.

-- Plaintext secret: valid-secret-uuid (same BCrypt hash as store-01)
INSERT INTO sites (id, account_id, domain, client_secret_hash, display_name, is_active, created_at, updated_at, site_name, client_api_version)
VALUES ('0199bab0-4444-4444-4444-444444444444', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890', 'store-v2.example.com', '$2a$10$w4ybRJqsZPH4IWHXHaN1rukDAfZc6Ri4P45Hpk3mlfbZpHIHYYyBm', 'Store V2 (Delta gRPC)', true, '2026-07-05 00:00:00', CURRENT_TIMESTAMP AT TIME ZONE 'UTC', 'store-v2.example.com', 'V2');

-- IN_PROGRESS batch owned by the V2 site — used to verify upload is rejected while
-- drain endpoints (complete/fail/cancel) keep working for batches opened before the flip.
INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count, total_size, has_errors, started_at, created_at, completed_at)
VALUES ('b1c2d3e4-f5a6-7890-bcde-f12345678907', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890', '0199bab0-4444-4444-4444-444444444444', 'IN_PROGRESS', 'a1b2c3d4-e5f6-7890-abcd-ef1234567890/store-v2.example.com/2026-07-05/10-00/', 1, 1024, false, CURRENT_TIMESTAMP AT TIME ZONE 'UTC', CURRENT_TIMESTAMP AT TIME ZONE 'UTC', NULL);

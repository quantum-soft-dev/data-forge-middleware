-- Rollback Migration: V10__rollback_indexes_for_phone_and_company.sql
-- IMPORTANT: Flyway Community Edition does NOT support automatic rollback.
-- This script is provided for manual rollback if needed.
--
-- Use Case: If indexes cause performance issues or need to be recreated
--
-- WARNING: This operation will:
-- - Drop indexes on phone and company columns
-- - Slow down queries that search by phone or company
-- - Free up disk space (~1-2% per 100K rows)
--
-- Prerequisites:
-- 1. Backup database (pg_dump)
-- 2. Verify no active queries using these indexes
-- 3. Plan for potential query performance degradation
--
-- Execution:
-- psql -U <user> -d <database> -f V10__rollback_indexes_for_phone_and_company.sql

-- Drop indexes
DROP INDEX IF EXISTS idx_accounts_phone;
DROP INDEX IF EXISTS idx_accounts_company;

-- Optional: Update flyway_schema_history to remove V10 entry (run separately)
-- DELETE FROM flyway_schema_history WHERE version = '10';

-- Verification:
-- SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'accounts';
-- Expected: idx_accounts_phone and idx_accounts_company should NOT appear

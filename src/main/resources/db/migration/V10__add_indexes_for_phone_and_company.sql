-- Migration: V10__add_indexes_for_phone_and_company.sql
-- Description: Add indexes for phone and company columns to improve search performance
-- Date: 2025-10-12
-- Author: Claude Code (PR #10 Review Response)
--
-- Context:
-- Phone and company fields were added in V9 migration.
-- These indexes are needed if admins will search/filter accounts by phone or company.
--
-- Performance Impact:
-- - SELECT queries with WHERE phone = '...' or WHERE company = '...' will use index scan instead of seq scan
-- - Minimal write performance impact (indexes updated on INSERT/UPDATE)
-- - Each index adds ~1-2% disk space overhead per 100K rows
--
-- Indexing Strategy:
-- - Partial indexes (WHERE column IS NOT NULL) to reduce index size
-- - ~30-50% smaller than full indexes since phone/company are optional fields
-- - Only rows with non-null values are indexed

-- Index on phone column (for searching by phone number)
-- Partial index: only indexes rows where phone IS NOT NULL (optional field)
CREATE INDEX idx_accounts_phone
    ON accounts(phone)
    WHERE phone IS NOT NULL;

-- Index on company column (for searching by company name)
-- Partial index: only indexes rows where company IS NOT NULL (optional field)
CREATE INDEX idx_accounts_company
    ON accounts(company)
    WHERE company IS NOT NULL;

-- Verification Queries:
-- EXPLAIN SELECT * FROM accounts WHERE phone = '+1234567890';
-- EXPLAIN SELECT * FROM accounts WHERE company = 'Acme Corp';
-- Expected: "Index Scan using idx_accounts_phone" or "Index Scan using idx_accounts_company"

-- V18: Optimize global errors index for sorting by occurred_at
-- Feature: 016-global-error-handling (optimization)
-- Date: 2026-01-11
--
-- ROLLBACK (manual):
-- DROP INDEX IF EXISTS idx_error_logs_global_unread_sorted;
-- DROP INDEX IF EXISTS idx_error_logs_global_all_sorted;
-- CREATE INDEX idx_error_logs_global_unread ON error_logs (site_id) WHERE batch_id IS NULL AND is_read = false;

-- Drop original index (only indexed site_id, sorting by occurred_at was a bottleneck)
DROP INDEX IF EXISTS idx_error_logs_global_unread;

-- Create optimized composite index with occurred_at for efficient sorting (unread only)
-- This index supports:
-- 1. Filtering by site_id (for account authorization via JOIN)
-- 2. Sorting by occurred_at DESC (most common sort order)
-- 3. Partial index for unread global errors only
CREATE INDEX idx_error_logs_global_unread_sorted
ON error_logs (site_id, occurred_at DESC)
WHERE batch_id IS NULL AND is_read = false;

-- Create index for "Show All" view (includes read errors)
-- Partial index on batch_id IS NULL covers all global errors regardless of read status
CREATE INDEX idx_error_logs_global_all_sorted
ON error_logs (site_id, occurred_at DESC)
WHERE batch_id IS NULL;

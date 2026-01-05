-- ============================================================================
-- Migration: V16 - Add compound index for batch account status queries
-- Feature: 015 - Plugin Reinit Option
-- Author: Claude Code
-- Date: 2026-01-05
-- ============================================================================
--
-- This index optimizes the findLatestCompletedByAccountId query used by:
-- - Plugin activation (auto SQL initialization)
-- - Plugin reinit endpoint (manual reinitialization)
--
-- Query pattern:
--   SELECT * FROM batches
--   WHERE account_id = ? AND status IN ('COMPLETED', 'COMPLETED_WITH_WARNINGS')
--   ORDER BY completed_at DESC LIMIT 1
--
-- Index covers: account_id lookup, status filter, completed_at ordering
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_batch_account_status_completed
ON batches(account_id, status, completed_at DESC);

COMMENT ON INDEX idx_batch_account_status_completed IS
    'Optimizes findLatestCompletedByAccountId for plugin SQL initialization';

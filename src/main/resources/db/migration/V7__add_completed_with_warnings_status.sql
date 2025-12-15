-- Migration: V7__add_completed_with_warnings_status.sql
-- Description: Add COMPLETED_WITH_WARNINGS status to batches table
-- Author: Data Forge Team
-- Date: 2025-12-15

-- Drop existing constraint
ALTER TABLE batches
DROP CONSTRAINT chk_batch_status;

-- Add updated constraint with new status
ALTER TABLE batches
ADD CONSTRAINT chk_batch_status
CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'COMPLETED_WITH_WARNINGS', 'NOT_COMPLETED', 'FAILED', 'CANCELLED'));

-- Update column comment
COMMENT ON COLUMN batches.status IS 'Lifecycle state (IN_PROGRESS, COMPLETED, COMPLETED_WITH_WARNINGS, NOT_COMPLETED, FAILED, CANCELLED)';

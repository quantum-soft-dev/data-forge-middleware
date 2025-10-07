-- V8: Add version column for optimistic locking
--
-- Changes:
-- 1. Add version column to batches table for JPA optimistic locking
-- 2. Initialize existing rows with version = 0
--
-- Purpose:
-- Prevents race conditions in batch timeout scheduler where multiple scheduler
-- instances might try to mark the same batch as EXPIRED simultaneously.

-- Add version column with default value 0
ALTER TABLE batches
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Add comment
COMMENT ON COLUMN batches.version IS 'Optimistic locking version for concurrent update prevention';

-- Note: JPA will automatically increment this version on each update

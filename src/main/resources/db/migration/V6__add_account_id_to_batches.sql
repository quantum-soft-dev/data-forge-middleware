-- Migration: V6__add_account_id_to_batches.sql
-- Description: Add account_id column to batches table for direct querying
-- Author: Data Forge Team
-- Date: 2025-10-06

-- Add account_id column (nullable initially to allow existing data)
ALTER TABLE batches ADD COLUMN account_id UUID;

-- Update existing rows to populate account_id from sites table
UPDATE batches b
SET account_id = s.account_id
FROM sites s
WHERE b.site_id = s.id;

-- Make account_id NOT NULL after backfilling
ALTER TABLE batches ALTER COLUMN account_id SET NOT NULL;

-- Add foreign key constraint
ALTER TABLE batches ADD CONSTRAINT fk_batches_account_id
    FOREIGN KEY (account_id) REFERENCES accounts(id);

-- Add index for performance on account_id queries
CREATE INDEX idx_batches_account_id ON batches(account_id);

-- Add comment
COMMENT ON COLUMN batches.account_id IS 'Denormalized account reference for efficient querying';

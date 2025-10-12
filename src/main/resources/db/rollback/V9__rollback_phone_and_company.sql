-- Rollback Migration: V9__rollback_phone_and_company.sql
-- Description: Rollback V9 migration - removes phone and company fields from accounts table
-- Author: Data Forge Team
-- Date: 2025-10-12
--
-- IMPORTANT: Flyway Community Edition does NOT support automatic rollback.
-- This script is provided for manual rollback if needed.
--
-- Usage:
--   1. Ensure you have a database backup
--   2. Run this script manually: psql -U postgres -d dfm -f V9__rollback_phone_and_company.sql
--   3. Update flyway_schema_history table to remove V9 entry
--
-- WARNING: This will permanently delete phone and company data from all accounts!

-- Remove columns added in V9 migration
ALTER TABLE accounts
    DROP COLUMN IF EXISTS phone,
    DROP COLUMN IF EXISTS company;

-- Update flyway_schema_history to remove V9 entry (optional, run separately)
-- DELETE FROM flyway_schema_history WHERE version = '9';

-- Migration: V9__add_phone_and_company_to_accounts.sql
-- Description: Add phone and company optional fields to accounts table
-- Author: Data Forge Team
-- Date: 2025-10-12

ALTER TABLE accounts
    ADD COLUMN phone VARCHAR(50),
    ADD COLUMN company VARCHAR(255);

-- Comments for documentation
COMMENT ON COLUMN accounts.phone IS 'Account phone number (optional)';
COMMENT ON COLUMN accounts.company IS 'Account company name (optional)';

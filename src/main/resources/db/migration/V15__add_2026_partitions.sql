-- Migration: V15__add_2026_partitions.sql
-- Description: Add automatic partition creation functions and partitions for 2026+
-- Author: Data Forge Team
-- Date: 2026-01-01
--
-- This migration:
-- 1. Creates a reusable function to ensure partitions exist (idempotent)
-- 2. Creates partitions for current and next month at migration time
-- 3. The function can be called by a scheduled job to create future partitions

-- ============================================================
-- Function to ensure partitions exist for a given date (idempotent)
-- Can be called manually or by scheduled job
-- ============================================================
CREATE OR REPLACE FUNCTION ensure_partitions_exist(
    target_date DATE DEFAULT CURRENT_DATE
)
RETURNS void AS $$
DECLARE
    partition_date DATE;
    partition_name TEXT;
    start_date DATE;
    end_date DATE;
BEGIN
    -- Get first day of the month
    partition_date := DATE_TRUNC('month', target_date)::DATE;
    start_date := partition_date;
    end_date := partition_date + INTERVAL '1 month';

    -- Create error_logs partition if not exists
    partition_name := 'error_logs_' || TO_CHAR(partition_date, 'YYYY_MM');
    IF NOT EXISTS (
        SELECT 1 FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE c.relname = partition_name AND n.nspname = 'public'
    ) THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF error_logs FOR VALUES FROM (%L) TO (%L)',
            partition_name, start_date, end_date
        );
        RAISE NOTICE 'Created error_logs partition: %', partition_name;
    END IF;

    -- Create plugin_audit_logs partition if not exists
    partition_name := 'plugin_audit_logs_' || TO_CHAR(partition_date, 'YYYY_MM');
    IF NOT EXISTS (
        SELECT 1 FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE c.relname = partition_name AND n.nspname = 'public'
    ) THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF plugin_audit_logs FOR VALUES FROM (%L) TO (%L)',
            partition_name, start_date, end_date
        );
        RAISE NOTICE 'Created plugin_audit_logs partition: %', partition_name;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- ============================================================
-- Create partitions for current month and next 12 months
-- This ensures we have partitions for the foreseeable future
-- ============================================================
DO $$
DECLARE
    i INTEGER;
    target_date DATE;
BEGIN
    FOR i IN 0..12 LOOP
        target_date := CURRENT_DATE + (i || ' months')::INTERVAL;
        PERFORM ensure_partitions_exist(target_date);
    END LOOP;
END $$;

-- ============================================================
-- Comments
-- ============================================================
COMMENT ON FUNCTION ensure_partitions_exist(DATE) IS
    'Ensures error_logs and plugin_audit_logs partitions exist for a given month (idempotent). '
    'Call with CURRENT_DATE for current month, or future dates to pre-create partitions. '
    'Can be called by a scheduled job: SELECT ensure_partitions_exist(CURRENT_DATE + INTERVAL ''1 month'')';

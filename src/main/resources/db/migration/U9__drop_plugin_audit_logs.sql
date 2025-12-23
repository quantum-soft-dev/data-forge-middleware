-- U9__drop_plugin_audit_logs.sql
-- Undo migration for V9__create_plugin_audit_logs_partitioned.sql
-- WARNING: This will delete all plugin audit log data!

-- Drop the partition creation function
DROP FUNCTION IF EXISTS create_plugin_audit_logs_partition(DATE);

-- Drop the partitioned table (automatically drops all partitions)
DROP TABLE IF EXISTS plugin_audit_logs CASCADE;

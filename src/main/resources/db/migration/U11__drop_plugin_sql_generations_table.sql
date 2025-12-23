-- U11__drop_plugin_sql_generations_table.sql
-- Undo migration for V11__create_plugin_sql_generations_table.sql
-- WARNING: This will delete all plugin SQL generation data!

-- Drop indexes first
DROP INDEX IF EXISTS idx_sql_gen_site_created;
DROP INDEX IF EXISTS idx_sql_gen_account_plugin;

-- Drop the table (CASCADE will handle dependent constraints)
DROP TABLE IF EXISTS plugin_sql_generations CASCADE;

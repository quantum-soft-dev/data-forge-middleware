-- U8__drop_plugin_tables.sql
-- Undo migration for V8__create_plugin_tables.sql
-- WARNING: This will delete all plugin configuration and activation data!

-- Note: Must run U11, U10, U9 first if those migrations were applied

-- Drop account_plugins first (has foreign key to plugin_configs)
DROP TABLE IF EXISTS account_plugins CASCADE;

-- Drop plugin_configs
DROP TABLE IF EXISTS plugin_configs CASCADE;

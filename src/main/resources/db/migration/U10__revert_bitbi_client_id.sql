-- U10__revert_bitbi_client_id.sql
-- Undo migration for V10__update_bitbi_plugin_client_id.sql
-- Reverts Bit BI plugin client_id to placeholder value

UPDATE plugin_configs
SET client_id = 'BITBI_CLIENT_ID_PLACEHOLDER',
    updated_at = NOW()
WHERE plugin_id = 'bit-bi';

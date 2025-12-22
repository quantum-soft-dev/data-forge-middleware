-- V10: Update Bit BI plugin client_id from placeholder to actual Auth0 M2M client ID
-- This replaces the placeholder value with the real Bit BI Integration M2M application client ID

UPDATE plugin_configs
SET client_id = 'vlyrbKacW1kdcF5srxVYe99wN2UrmZKH',
    updated_at = NOW()
WHERE plugin_id = 'bit-bi'
  AND client_id = 'BITBI_CLIENT_ID_PLACEHOLDER';

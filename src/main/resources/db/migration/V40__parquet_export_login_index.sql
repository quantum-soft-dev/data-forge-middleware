-- V40: Parquet Export Basic Auth login lookup (028 review).
-- The login lives in account_plugins.plugin_data ->> 'login'; Basic Auth resolves the
-- activation with a point query, so the expression needs an index — and making it UNIQUE
-- also enforces the login-uniqueness invariant the credential minting relies on
-- (SecureRandom collisions are cosmically unlikely, but a duplicate would silently make
-- one activation unauthenticatable depending on scan order).
CREATE UNIQUE INDEX idx_account_plugins_parquet_export_login
    ON account_plugins ((plugin_data ->> 'login'))
    WHERE plugin_id = 'parquet-export';

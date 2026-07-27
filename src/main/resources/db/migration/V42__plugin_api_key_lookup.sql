-- V42 (031): indexable lookup key for Bit BI plugin API keys.
--
-- Until now every Plugin API request loaded all active bit-bi activations and ran one BCrypt
-- comparison per row (O(n) BCrypt per request, ~100 ms each). This column stores the hex
-- SHA-256 of the raw API key so validation can resolve the activation with a single indexed
-- point query and then verify with exactly one BCrypt comparison.
--
-- The BCrypt hash in plugin_data -> 'apiKeyHash' stays the source of truth for verification;
-- the digest here is only an index key. API keys are 'plk_' + 32 chars drawn by SecureRandom
-- from a 62-symbol alphabet (~190 bits), so an unsalted digest is not brute-forceable and
-- rainbow tables do not apply.
--
-- Nullable on purpose: existing rows cannot be backfilled (only the BCrypt hash is stored,
-- the plaintext key is gone). Such rows keep authenticating through the legacy fallback scan
-- and acquire a lookup on their next key rotation.
ALTER TABLE account_plugins
    ADD COLUMN api_key_lookup VARCHAR(64);

-- Partial so the (many) rows without a lookup do not collide with each other; unique so a
-- digest can never resolve to two activations (which would make authentication depend on
-- scan order). Not scoped to bit-bi: a lookup value must be globally unambiguous.
CREATE UNIQUE INDEX idx_account_plugins_api_key_lookup
    ON account_plugins (api_key_lookup)
    WHERE api_key_lookup IS NOT NULL;

COMMENT ON COLUMN account_plugins.api_key_lookup IS
    'Hex SHA-256 of the plugin API key, used only as an indexed lookup handle; verification still uses the BCrypt hash in plugin_data.';

# 031 — Indexed Plugin API key lookup (bit-bi)

## Problem

`PluginApiKeyService.validateApiKey()` authenticates every Bit BI Plugin API request by
loading **all** active `bit-bi` activations (`findActiveByPluginId`) and running
`BCryptPasswordEncoder.matches()` against each one until a hit. Cost is O(n) BCrypt
comparisons per request, where n = number of accounts with the plugin active. A single
BCrypt comparison costs ~100 ms, so the javadoc promise of "<50ms (SC-004)" was already
unreachable at n = 1.

Worse, `getStoredApiKeyHash()` calls `passwordEncoder.encode()` **on the read path** for
legacy rows that still hold the plaintext key under `plugin_data -> 'apiKey'`: it mints a
fresh BCrypt hash (~100 ms, random salt) only to feed it back into `matches()`. Because the
salt is random, `matches(candidate, encode(stored))` is true only when
`candidate.equals(stored)` — i.e. the most expensive possible string equality check.

Unauthenticated traffic reaches this code (the filter validates before anything else), so a
caller sending well-formed garbage keys forces the full scan on every request.

## Reference implementation

The `parquet-export` plugin (028) already solves the same problem correctly:
`ParquetExportCredentialsService.validate()` resolves the activation with one indexed point
query (`findActiveByPluginIdAndLogin`, backed by the unique expression index from V40) and
then performs exactly one BCrypt comparison.

Bit BI cannot copy it verbatim: parquet-export stores the *login* in clear, and only the
password is hashed. A Bit BI API key is a single opaque secret with nothing to look up by.

## Solution

Add an indexable lookup column derived from the key itself.

- `account_plugins.api_key_lookup` = lowercase hex SHA-256 of the raw API key, nullable,
  with a unique partial index (`WHERE api_key_lookup IS NOT NULL`).
- Validation: format check → point query by `(plugin_id, api_key_lookup)` → **one** BCrypt
  comparison against the stored `apiKeyHash`.
- BCrypt stays the verification source of truth; SHA-256 is only an index key. The key is
  `plk_` + 32 chars from a 62-symbol alphabet (~190 bits of entropy from `SecureRandom`),
  so precomputation/rainbow tables do not apply and an unsalted digest is safe here.
- The lookup is written on key generation and on rotation.

### Backfill

Impossible: only the BCrypt hash is stored, so existing rows cannot have their lookup
computed offline. Rows without a lookup keep working through a legacy fallback scan and
migrate naturally on the next key rotation. A counter makes the remaining legacy traffic
observable so the fallback can be deleted once it reads zero.

## Success criteria

- **SC-001** A valid key whose row has a lookup costs exactly one BCrypt comparison and one
  indexed query, independent of the number of activations.
- **SC-002** A well-formed but unknown key performs no BCrypt work at all when no legacy
  rows exist.
- **SC-003** A malformed key is rejected before any database access.
- **SC-004** Legacy rows (no lookup) still authenticate, and every such authentication is
  counted in `plugin.api.key.validation.legacy.hit`.
- **SC-005** `passwordEncoder.encode()` no longer appears on the validation path.
- **SC-006** Rotation updates both the BCrypt hash and the lookup; the previous key stops
  authenticating immediately.
- **SC-007** Deactivated activations never authenticate.

## Out of scope

- Caching of validation results.
- Opportunistic self-healing (writing the lookup during a successful legacy validation) —
  see `tasks.md` notes; rejected to keep the read path read-only.
- The `parquet-export` credential path (already O(1)).

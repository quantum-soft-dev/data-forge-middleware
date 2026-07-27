# CR: Indexed Plugin API Key Lookup (031)

**Status**: Implemented (branch `feature/031-plugin-api-key-lookup`)
**Spec**: `specs/031-plugin-api-key-lookup/`
**Migration**: V42
**Scope**: Bit BI Plugin API authentication (`X-Plugin-Api-Key`). The `parquet-export` Basic Auth
path (028) was already O(1) and is untouched.

## Motivation

Every request to `/api/v1/plugins/bit-bi/**` authenticated like this:

```java
List<AccountPlugin> activePlugins = accountPluginRepository.findActiveByPluginId("bit-bi");
for (AccountPlugin accountPlugin : activePlugins) {
    String storedHash = getStoredApiKeyHash(accountPlugin);   // <-- see below
    if (storedHash != null && passwordEncoder.matches(apiKeyValue, storedHash)) { ... }
}
```

Two problems, both on the hot path and both reachable by unauthenticated callers (the filter
validates the key before anything else runs):

1. **O(n) BCrypt per request.** One BCrypt comparison costs ~100 ms by design. Every account with
   the plugin active added another ~100 ms to the worst case, and a *wrong* key always hit the
   worst case — it scanned all of them. Anyone could turn a stream of well-formed garbage keys
   into `n × 100 ms` of CPU per request.
2. **BCrypt `encode()` on a read path.** For activations still holding a plaintext key
   (`plugin_data -> 'apiKey'`, the pre-hash format), `getStoredApiKeyHash()` called
   `passwordEncoder.encode(storedPlaintext)` — minting a fresh ~100 ms hash *with a random salt*
   — and then fed it to `matches()`. With a random salt, `matches(candidate, encode(stored))` is
   true exactly when `candidate.equals(stored)`. It was the most expensive string equality check
   in the codebase, executed per legacy row per request.

The javadoc claimed "Performance requirement: <50ms (SC-004)", which a single BCrypt comparison
already makes impossible.

## Design

Same shape as `ParquetExportCredentialsService.validate()` (028): resolve the row with one
indexed point query, then verify with exactly one BCrypt comparison. Parquet Export can look up
by *login* because its login is not a secret; a Bit BI key is a single opaque secret, so we
derive a lookup handle from the key itself.

- **`account_plugins.api_key_lookup`** (V42) stores the lowercase hex **SHA-256 of the raw API
  key**. Nullable, with a **unique partial index** (`WHERE api_key_lookup IS NOT NULL`).
- Validation: format check → `findActiveByPluginIdAndApiKeyLookup(pluginId, sha256(key))` →
  **one** `passwordEncoder.matches()` against `plugin_data -> 'apiKeyHash'`.
- **BCrypt remains the verification source of truth.** SHA-256 is only an index key; a row
  selected by lookup still has to pass the BCrypt comparison, and the service additionally
  re-checks `isActive()`.

### Why an unsalted SHA-256 is safe here

The threat that salting defends against is offline brute force / precomputation over
*low-entropy, human-chosen* secrets. An API key is `plk_` + 32 characters drawn by
`SecureRandom` from a 62-symbol alphabet — about 190 bits. There is no dictionary to try and no
rainbow table to build. The digest also never authenticates anything on its own.

### Why there is no backfill

Existing rows only have the BCrypt hash; the plaintext is gone, so their lookup cannot be
computed. Those rows stay on a **fallback scan restricted to activations with a null lookup**
(`findActiveByPluginIdWithoutApiKeyLookup`) and move onto the indexed path when their key is next
rotated. The fallback also stopped calling `encode()`: plaintext keys are now compared with
`MessageDigest.isEqual` (constant time, no hashing).

Considered and rejected: **opportunistically writing the lookup during a successful legacy
validation**, which would drain the legacy set without any client action. It turns the read path
(`@Transactional(readOnly = true)`, executed inside an auth filter) into a write path, and the
same drain happens deterministically through rotation. Worth revisiting if the legacy counter
refuses to fall.

## Cost after 031

| Request | Before | After |
|---|---|---|
| Valid key | up to n BCrypt | 1 indexed query + 1 BCrypt |
| Well-formed unknown key | n BCrypt (always the full scan) | 1 indexed query, **0 BCrypt** |
| Malformed key | rejected before DB | rejected before DB |
| Legacy (pre-V42) key | n BCrypt + 1 encode per plaintext row | scan of legacy rows only, no encode |

The promise is **constant cost per request**, not a latency budget: one BCrypt comparison is
~100 ms on purpose, and that is what a validated request costs.

## Draining the legacy path

Two counters make the residue visible:

| Metric | Meaning |
|---|---|
| `plugin.api.key.validation.legacy.scan` | Requests that fell through to the pre-V42 scan (unindexed rows exist, or someone is probing with bad keys) |
| `plugin.api.key.validation.legacy.hit` | Requests actually **authenticated** by the scan — keys that would break if the fallback were deleted today |

Procedure: watch `legacy.hit`; have the remaining accounts re-issue their key; once `legacy.hit`
stays at zero for longer than any plausible client key lifetime, delete
`validateAgainstLegacyActivations()` and `findActiveByPluginIdWithoutApiKeyLookup`.

Re-issuing a Bit BI key today means **deactivate then activate** the plugin: `BitBiPlugin`
generates the key in `onActivate()`, and the raw value is returned only for new activations and
reactivations. Note two pre-existing warts on that path, left as follow-ups because they are
outside this change:

- Calling activate on an *already active* plugin also runs `onActivate()` and therefore issues a
  new key, but the response withholds it — the account silently loses API access until it
  deactivates and activates again.
- `PluginApiKeyService.rotateApiKey(accountId)` exists and does the right thing, but nothing
  exposes it over HTTP. A `POST /api/v1/account/plugins/bit-bi/rotate-key` mirroring
  `parquet-export/rotate-password` would make the drain a one-call operation.

## Incidental fixes

- `AccountPlugin.updatePluginData()` **merges**, so the long-standing "remove the legacy
  plaintext key on rotation" step (`updatedData.remove("apiKey")`) never removed anything —
  rotated activations kept their old plaintext secret in `plugin_data` indefinitely. Deletion is
  now explicit via `AccountPlugin.removePluginData()`.
- `generateApiKeyForAccount()` no longer re-loads by id an activation it has already fetched.
- Removed the unused `findByPluginIdAndApiKey` JSONB containment query, which looked up the
  retired plaintext `apiKey` field and had no callers.

## Migration

`V42__plugin_api_key_lookup.sql` — forward-only and additive: one nullable column plus one unique
partial index. No data rewrite, no downtime, and old application instances keep working against
the migrated schema (they simply ignore the column).

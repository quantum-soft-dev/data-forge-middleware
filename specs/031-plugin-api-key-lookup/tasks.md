# Tasks — 031 Indexed Plugin API key lookup

WIP = 1. Every task is test-first (red → green → one atomic commit). Gate before each
commit: `./gradlew test -PexcludeIntegration` must be 100% green.

Scope guard: only `src/main/java/com/bitbi/dfm/plugin/**`,
`src/main/resources/db/migration/**`, `src/test/java/com/bitbi/dfm/plugin/**`, `docs/`,
`specs/031-plugin-api-key-lookup/**`.

---

## T01 — Migration V42 + entity column

**Tests first**: migration-shape test asserting `V42__plugin_api_key_lookup.sql` exists, is
additive (nullable column, no `UPDATE`/`DROP`), and declares a unique partial index; entity
test asserting `AccountPlugin` exposes the lookup and that `activate()` leaves it null.

**Implementation**
- `V42__plugin_api_key_lookup.sql`: `ALTER TABLE account_plugins ADD COLUMN api_key_lookup
  VARCHAR(64);` + `CREATE UNIQUE INDEX ... ON account_plugins (api_key_lookup) WHERE
  api_key_lookup IS NOT NULL;` Forward-only, additive, no data change.
- `AccountPlugin.apiKeyLookup` field (`@Column(name = "api_key_lookup", length = 64)`) +
  domain mutator.
- `PluginApiKey.lookupOf(String)` — lowercase hex SHA-256 of the raw key.

V42 is owned exclusively by this feature in this round (last applied migration is V41).

Commit: `feat(plugin): api key lookup column and V42 migration (T01)`

---

## T02 — Populate the lookup on generation and rotation

**Tests first**: `PluginApiKeyServiceTest` — generation stores `apiKeyHash` **and**
`api_key_lookup = sha256(raw key)`; the legacy plaintext `apiKey` field is dropped;
rotation replaces both hash and lookup so the previous key no longer resolves.

**Implementation**: `PluginApiKeyService.generateApiKey()` sets the lookup alongside the
hash (rotation already delegates to it).

Commit: `feat(plugin): store api key lookup on generation and rotation (T02)`

---

## T03 — Point-query validation, legacy scan only as fallback

**Tests first**:
- valid key with a lookup → success with **exactly one** BCrypt comparison and no call to
  `findActiveByPluginId`;
- well-formed unknown key with no legacy rows → empty, zero BCrypt comparisons;
- malformed key → empty without touching the repository;
- legacy row (no lookup, `apiKeyHash` present) → still authenticates via the fallback scan;
- legacy row with plaintext `apiKey` → still authenticates, and `passwordEncoder.encode()`
  is never invoked during validation;
- inactive activation → rejected.

**Implementation**: `validateApiKey()` = format check → `findActiveByPluginIdAndApiKeyLookup`
→ single BCrypt compare. On miss, scan only rows *without* a lookup
(`findActiveByPluginIdWithoutApiKeyLookup`). Plaintext legacy rows are compared with a
constant-time byte comparison instead of `encode()`.

Commit: `feat(plugin): indexed api key lookup (T03)`

---

## T04 — Legacy fallback metric + honest javadoc

**Tests first**: `plugin.api.key.validation.legacy.scan` increments once per fallback scan;
`plugin.api.key.validation.legacy.hit` increments only when a legacy row authenticates; the
fast path increments neither.

**Implementation**: counters + javadoc rewritten to describe actual cost (one indexed query
+ one BCrypt comparison, BCrypt is deliberately ~100 ms) instead of the unreachable
"<50ms (SC-004)" promise.

Commit: `feat(plugin): legacy api key fallback metric (T04)`

---

## T05 — Documentation

`docs/cr-plugin-api-key-lookup.md`: problem, chosen design, migration, operational note on
draining the legacy path (watch `legacy.hit`, rotate keys, then delete the fallback).
Cross-reference from the plugin guide.

Commit: `docs(plugin): document indexed api key lookup (T05)`

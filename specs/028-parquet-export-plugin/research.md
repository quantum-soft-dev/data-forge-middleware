# Phase 0 Research: Parquet Export Plugin

## R1. One-time download links vs S3 presigned URLs

**Decision**: Middleware-registered tokens with atomic consumption; presigned URL minted only at consume time with a 60 s signature duration.

**Rationale**: S3 presigned URLs are stateless signatures — S3 cannot enforce single use. The only reliable single-use gate is a database row transition. Pattern:

```sql
UPDATE download_links
   SET consumed_at = now()
 WHERE token = :token
   AND consumed_at IS NULL
   AND expires_at > now()
-- affected rows = 1 → winner (302), = 0 → distinguish 404/410 by follow-up SELECT
```

A follow-up `SELECT` (only on the 0-rows path) distinguishes unknown token (404) from consumed/expired (410). The presigned URL's 60 s window is the residual exposure: after redirect the URL is copyable but dies in a minute.

**Alternatives considered**:
- *Short-TTL presigned URLs returned directly in the listing* — rejected: not one-time; a leaked listing response is replayable for the full TTL.
- *Proxy-streaming the file through the app with token check* — rejected: defeats the point of S3 offload; 500 MB-scale files through the JVM.
- *S3 object tagging / delete-after-read* — rejected: destructive, racy, files are shared with other consumers (owner UI downloads).

## R2. Basic Auth without Spring's UserDetailsService

**Decision**: Custom `OncePerRequestFilter` (`ParquetExportBasicAuthFilter`) modeled on `PluginApiKeyAuthenticationFilter`: parse `Authorization: Basic`, base64-decode, split on first `:`, look up the active `parquet-export` activation by login, single BCrypt match, then place a pre-authenticated token (`ROLE_PLUGIN_CLIENT`, principal = accountId, detail = accountPluginId) into the `SecurityContext`. 401 responses carry `WWW-Authenticate: Basic realm="parquet-export"` so generic HTTP clients re-prompt.

**Rationale**: `httpBasic()` + `UserDetailsService` would force a parallel user store abstraction over JSONB plugin data; the repo precedent (API-key filter writing its own 401 JSON) is simpler and already tested. Login-first lookup gives exactly one BCrypt verification per request — deliberately better than Bit BI's O(n) hash scan (noted in its own code as a scale concern).

**Login lookup**: `findActiveByPluginId("parquet-export")` (existing repo method) then match `plugin_data->>'login'` in memory. Activation counts are small (one per account); if it ever grows, a JSONB GIN expression index on `plugin_data->>'login'` is a follow-up, not a blocker.

## R3. Filter chain placement

**Decision**: New `@Bean @Order(4)` chain matching `/api/v1/plugins/parquet-export/**`; existing `adminApiFilterChain` and later chains shift by one (or the new chain takes 3.5-style renumbering — final numbering assigned in implementation, keeping `SecurityFilterChainTest` assertions authoritative). `/files` → authenticated via the Basic filter; `/download/**` → `permitAll` (token is the credential). The admin catch-all gets `requestMatchers("/api/v1/plugins/parquet-export/**").denyAll()` (existing convention for carve-outs). `TestSecurityConfig` mirrors the chain.

**Rationale**: The catch-all `/api/v1/**` chain (currently order 4) would otherwise swallow the routes and demand an Auth0 JWT. The Bit BI chain (order 3) narrowly matches its own paths, so a sibling chain is the established pattern.

## R4. Deriving the file catalog

**Decision**:
- **Delta files**: `changelog_segments` rows with `egress_at IS NOT NULL AND egress_at > :since`, `site_id` ∈ account's sites (join `sites` on `account_id`). Tables per segment = keys of `stats` JSONB (fallback: skip segments with null stats — pre-stats era rows have no per-table info and predate Parquet egress anyway). Key derived via `S3CheckpointStorage.deltaKey(siteId, table, firstSeq, lastSeq)`; `deltaExists` HEAD probe drops schema-less/poison tables (same filtering the owner endpoint `DeltaSegmentParquetQueryService` performs).
- **Checkpoint files**: `checkpoints` rows with `s3_key_parquet IS NOT NULL AND updated_at > :since`, same account scoping.

**Rationale**: No new bookkeeping table for produced Parquet files — the derivation is exactly what 025 shipped for the owner UI; reusing it keeps one source of truth. HEAD probes are bounded by page size (≤ 100/call) and S3 HEAD is cheap.

**Pagination**: deterministic ordering `(producedAt, id)` per type; delta and checkpoint lists are queried separately and merged by `producedAt` — or simpler, `type` defaults to both but pagination applies per underlying query with a combined page assembled in memory (page size ≤ 100 keeps this trivial). Exact mechanics in data-model.md.

## R5. Credential generation & rotation

**Decision**: `ParquetExportCredentials` value object: login `pex_` + 12 alphanumerics (`SecureRandom`), password 32 alphanumerics (~190 bits) shown once. Stored: `login` plaintext, `passwordHash` BCrypt (existing `BCryptPasswordEncoder` usage in `PluginApiKeyService`). `onActivate()` returns the raw secret string through the existing `ActivationResult.apiKey` channel — formatted as `login:password` so the single-shot display carries both. Rotation endpoint on the owner API (`POST /api/v1/account/plugins/parquet-export/rotate-password`, Auth0-authenticated) mirrors `rotateApiKey`.

**Rationale**: Reuses the proven "raw secret returned once via ActivationResult" pipe without changing the SPI; `login:password` is also exactly the Basic Auth userinfo format, convenient for clients.

## R6. Link invalidation on deactivation

**Decision**: consume-time check joins the owning `account_plugins` row and requires `active = true`. No mass UPDATE on deactivate.

**Rationale**: One indexed read at consume time; avoids a second bookkeeping path that can miss links registered concurrently with deactivation.

## R7. Purge job

**Decision**: `@Scheduled(fixedDelayString = "${plugin.parquet-export.purge-interval-ms:3600000}")` deleting rows where `(consumed_at IS NOT NULL OR expires_at < now()) AND created_at < now() - retention`. Retention default 7 days via `plugin.parquet-export.purge-retention-days`.

**Rationale**: Simple bulk `DELETE`; table stays small (bounded by listing traffic × TTL window + 7 d tail). Follows existing scheduler precedents (batch timeout, retention cleanup).

## R8. Presigner expiry overload

**Decision**: Add `generatePresignedUrl(String s3Key, String fileName, Duration expiry)` overload to `S3PresignedUrlService`; existing method delegates with the 15-minute default. No behavior change for existing callers.

## R9. Audit action types

**Decision**: Extend `PluginActionType` with `FILES_LISTED` (metadata: filters, fileCount), `LINK_CONSUMED` (metadata: fileName, s3Key), `LINK_REJECTED` (metadata: reason consumed|expired|unknown|inactive), `PASSWORD_ROTATED`. Enum is stored as text in the partitioned audit table — additive, no migration needed. The `PluginAuditFilter` covers only Bit BI paths, so the parquet-export controller audits explicitly through `PluginAuditService` (deliberate: gives structured metadata instead of generic HTTP capture).

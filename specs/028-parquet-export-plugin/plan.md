# Implementation Plan: Parquet Export Plugin (Basic Auth + One-Time Download Links)

**Branch**: `028-parquet-export-plugin` | **Date**: 2026-07-27 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/028-parquet-export-plugin/spec.md`

## Summary

A second `Plugin` SPI implementation, `parquet-export`, that lets an external client pull Delta-v2 Parquet files. Activation mints Basic Auth credentials (generated login `pex_…`, BCrypt-hashed password shown once — same JSONB pattern as the Bit BI API key). A Basic-Auth-protected listing endpoint returns Parquet files (delta segments from `changelog_segments`, checkpoint snapshots from `checkpoints`) filtered by `since`/`siteId`/`table`/`type`, and registers a **one-time download link** per file in a new `download_links` table (V39). The anonymous download endpoint consumes a link atomically and 302-redirects to a ~60 s S3 presigned URL. A scheduled job purges stale links.

## Technical Context

**Language/Version**: Java 25 (LTS), Spring Boot 3.5.6, Spring Security 6
**Primary Dependencies**: Spring Data JPA, AWS SDK v2 (S3 + S3Presigner), Bucket4j + Caffeine (rate limiting), Hypersistence Utils (JSONB), Flyway 11
**Storage**: PostgreSQL 16 (new table `download_links`, migration **V39**); S3 objects already produced by Delta v2 egress/checkpointing (read-only for this feature)
**Testing**: JUnit 5 + Mockito (unit), MockMvc (contract), Testcontainers PostgreSQL + LocalStack (integration)
**Target Platform**: Linux server (GKE), same Spring Boot app
**Project Type**: single backend project (frontend explicitly out of scope)
**Performance Goals**: listing page ≤ 100 files per call; link registration is one batched insert per listing call; download consume is a single-row atomic UPDATE — no measurable load added
**Constraints**: presigned URLs cannot be one-time at S3 level → middleware token layer provides single-use semantics; delta Parquet keys are derived (never persisted) and may not exist for schema-less/poison tables → existence probe before listing
**Scale/Scope**: same order as Bit BI plugin traffic (≤ 100 req/min/account); `download_links` bounded by TTL (1 h) + purge (7 d)

## Constitution Check

*The spec-kit constitution is intentionally empty; CLAUDE.md is the governing policy.* Gates applied:

- ✅ Feature branch `028-parquet-export-plugin` off `develop`; lands as one squashed PR.
- ✅ TDD task-by-task (WIP = 1), per-task gate `./gradlew test -PexcludeIntegration`, before-PR gate `./gradlew integrationTest`.
- ✅ Flyway forward-only, next number **V39** (current V38).
- ✅ DDD package-by-layered-feature: new code lives in `plugin/` (domain → application → infrastructure → presentation); delta data accessed via existing `delta/` repositories, no persistence-layer duplication.
- ✅ Strangler rule not implicated (new surface, no API forked).
- ✅ Feature must be documented in `docs/` (guide: `docs/parquet-export-plugin-guide.md`).

## Project Structure

### Documentation (this feature)

```text
specs/028-parquet-export-plugin/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── parquet-export-api.md
└── tasks.md             # Phase 2 output (/tasks command)
```

### Source Code (repository root)

```text
src/main/java/com/bitbi/dfm/
├── plugin/
│   ├── domain/
│   │   ├── ParquetExportCredentials.java        # value object: login + raw password generation
│   │   ├── DownloadLink.java                    # entity (download_links)
│   │   ├── DownloadLinkRepository.java          # interface
│   │   └── PluginActionType.java                # + FILES_LISTED, LINK_CONSUMED, LINK_REJECTED, PASSWORD_ROTATED
│   ├── application/
│   │   ├── ParquetExportPlugin.java             # Plugin SPI impl (id "parquet-export")
│   │   ├── ParquetExportCredentialsService.java # mint/rotate/validate credentials (BCrypt in plugin_data)
│   │   ├── ParquetExportFileService.java        # listing: delta + checkpoint files, account-scoped
│   │   ├── DownloadLinkService.java             # register links, consume atomically, presign 60 s
│   │   └── DownloadLinkPurgeScheduler.java      # @Scheduled purge
│   ├── infrastructure/
│   │   └── JpaDownloadLinkRepository.java       # + atomic consume UPDATE query
│   └── presentation/
│       ├── ParquetExportBasicAuthFilter.java    # Basic Auth → SecurityContext (pattern: PluginApiKeyAuthenticationFilter)
│       ├── ParquetExportApiController.java      # GET /files (Basic Auth), GET /download/{token} (anon)
│       └── dto/ParquetFileResponseDto.java etc.
├── batch/infrastructure/S3PresignedUrlService.java   # + overload generatePresignedUrl(s3Key, fileName, Duration)
├── shared/config/SecurityConfiguration.java          # + parquetExportFilterChain (before adminApiFilterChain) + denyAll entries
└── shared/presentation/ApiRoutes.java                # + PARQUET_EXPORT_PLUGIN_API

src/main/resources/
├── db/migration/V39__create_download_links_table.sql
└── application.yml                                    # plugin.parquet-export.* properties

src/test/java/
├── com/bitbi/dfm/plugin/…                             # unit tests per class
├── contract/ParquetExportApiContractTest.java         # MockMvc
├── integration/ParquetExportIntegrationTest.java      # Testcontainers end-to-end
└── com/bitbi/dfm/config/TestSecurityConfig.java       # mirrored chain
```

**Structure Decision**: single backend project; all new business code under the existing `plugin/` bounded context (the download-link concept belongs to the plugin domain — it is issued and owned by a plugin activation). Delta data is read through existing `delta/` domain repositories (`ChangelogSegmentRepository`, `CheckpointRepository`) — no new queries duplicated outside their home aggregate beyond read-only finder additions.

## Key Design Decisions (Phase 0 summary — see research.md)

1. **One-time semantics in middleware, not S3**: `download_links.consumed_at` + atomic `UPDATE … WHERE consumed_at IS NULL AND expires_at > now()` (`@Modifying` JPQL/native, returns affected rows). Winner of a concurrent race gets the 302; loser gets 410. Presigned URL minted only after a successful consume, with 60 s expiry.
2. **Basic Auth chain**: dedicated `SecurityFilterChain` ordered between the Bit BI chain (3) and the admin catch-all (4): matcher `/api/v1/plugins/parquet-export/**`; `/files` authenticated via custom `ParquetExportBasicAuthFilter` (401 + `WWW-Authenticate: Basic` on failure), `/download/**` `permitAll`. `adminApiFilterChain` gets matching `denyAll` entries; `TestSecurityConfig` mirrored; `SecurityFilterChainTest` extended.
3. **Credential storage**: `plugin_data = { "login": "pex_…", "passwordHash": "$2a$…" }`. Login lookup is O(activations of parquet-export) via `findActiveByPluginId` + in-memory login match (same approach as Bit BI key validation, but keyed by login first so only ONE BCrypt check per request — no O(n) hash scanning).
4. **Delta file existence**: derived key via `S3CheckpointStorage.deltaKey(siteId, table, firstSeq, lastSeq)`; tables per segment come from `segment.stats` keys; `deltaExists` HEAD probe filters skipped/poison tables (same as `DeltaSegmentParquetQueryService`).
5. **`producedAt` semantics**: delta → `egress_at`; checkpoint → `updated_at`. `since` filters strictly greater-than, so a client can safely checkpoint its own high-water mark.
6. **Links are not deduplicated** across listings; TTL + purge bound growth. Deactivation invalidates unconsumed links via `account_plugin_id` join check at consume time (link consume requires the owning activation to still be active).
7. **Config**: `plugin.parquet-export.link-ttl-seconds` (default 3600), `plugin.parquet-export.presign-ttl-seconds` (default 60), `plugin.parquet-export.purge-retention-days` (default 7), `plugin.parquet-export.base-url` (absolute URL prefix for one-time links; falls back to request-derived URL).

## Complexity Tracking

No constitutional violations; no entries.

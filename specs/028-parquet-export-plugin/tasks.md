# Tasks: Parquet Export Plugin (Basic Auth + One-Time Download Links)

**Input**: Design documents from `/specs/028-parquet-export-plugin/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/parquet-export-api.md, quickstart.md

**Policy (CLAUDE.md)**: strictly serial, WIP = 1, test-first per task, one atomic Conventional Commit per task referencing the task id. Per-task gate: `./gradlew test -PexcludeIntegration` must be 100% green before each commit. No [P] markers — parallelism is against repo policy.

## Format: `[ID] [Story] Description` — exact file paths included

---

## Phase 1: Foundation (blocking prerequisites)

- [X] **T001** [US1] Migration V39 + `DownloadLink` entity/repository skeleton
  - Tests first: `src/test/java/com/bitbi/dfm/plugin/domain/DownloadLinkTest.java` — factory `register(...)` generates UUID id, 43-char URL-safe token (from 32 `SecureRandom` bytes, no padding), stamps `createdAt`/`expiresAt = createdAt + ttl`, null `consumedAt`; token uniqueness across generations (statistical: 1000 tokens distinct).
  - Implement: `src/main/resources/db/migration/V39__create_download_links_table.sql` (table + unique token index + purge/account indexes + `plugin_configs` seed row for `parquet-export`, per data-model.md); `src/main/java/com/bitbi/dfm/plugin/domain/DownloadLink.java` (JPA entity, Lombok `@Getter @NoArgsConstructor`, static factory); `src/main/java/com/bitbi/dfm/plugin/domain/DownloadLinkRepository.java` (interface: `save`, `saveAll`, `findByToken`, `consume(token, now)`, `purge(cutoff)`); `src/main/java/com/bitbi/dfm/plugin/infrastructure/JpaDownloadLinkRepository.java` (Spring Data + `@Modifying` native `consume` UPDATE with the `account_plugins.is_active` EXISTS guard + `purge` DELETE, per data-model.md).
  - Commit: `feat(plugin): download_links table + DownloadLink entity (T001)`

- [X] **T002** [US1] `ParquetExportCredentials` value object
  - Tests first: `src/test/java/com/bitbi/dfm/plugin/domain/ParquetExportCredentialsTest.java` — `generate()` produces login matching `^pex_[a-zA-Z0-9]{12}$` and password matching `^[a-zA-Z0-9]{32}$`; `basicAuthValue()` returns `login:password`; `toString()` masks the password.
  - Implement: `src/main/java/com/bitbi/dfm/plugin/domain/ParquetExportCredentials.java` (record, `SecureRandom`, pattern: `PluginApiKey`).
  - Commit: `feat(plugin): ParquetExportCredentials value object (T002)`

- [X] **T003** [US1] Extend `PluginActionType` + presigner expiry overload
  - Tests first: extend `src/test/java/com/bitbi/dfm/batch/infrastructure/S3PresignedUrlServiceTest.java` (or create if absent) — overload `generatePresignedUrl(s3Key, fileName, Duration)` passes the custom duration to the presigner; existing 2-arg method keeps 15 min.
  - Implement: add `FILES_LISTED`, `LINK_CONSUMED`, `LINK_REJECTED`, `PASSWORD_ROTATED` to `src/main/java/com/bitbi/dfm/plugin/domain/PluginActionType.java`; add `Duration` overload in `src/main/java/com/bitbi/dfm/batch/infrastructure/S3PresignedUrlService.java` (2-arg delegates).
  - Commit: `feat(plugin): audit action types + presign expiry overload (T003)`

---

## Phase 2: User Story 1 — Activation & credentials (P1)

- [X] **T004** [US1] `ParquetExportCredentialsService` (mint / rotate / validate)
  - Tests first: `src/test/java/com/bitbi/dfm/plugin/application/ParquetExportCredentialsServiceTest.java` (Mockito) — `mintCredentials(accountPlugin)` stores `login` plaintext + BCrypt `passwordHash` in plugin_data and returns raw credentials; minting is skipped when login already exists (idempotent re-activation); `rotatePassword(accountId)` keeps login, replaces hash, returns new raw password, 404-style exception when inactive/absent; `validate(login, rawPassword)` finds the active `parquet-export` activation by login (via `AccountPluginRepository.findActiveByPluginId`), exactly one BCrypt match, returns accountId+accountPluginId; wrong password / unknown login / inactive activation → empty.
  - Implement: `src/main/java/com/bitbi/dfm/plugin/application/ParquetExportCredentialsService.java` (pattern: `PluginApiKeyService`; audit `PASSWORD_ROTATED` via `PluginAuditService`).
  - Commit: `feat(plugin): parquet-export credentials service (T004)`

- [X] **T005** [US1] `ParquetExportPlugin` SPI implementation
  - Tests first: `src/test/java/com/bitbi/dfm/plugin/application/ParquetExportPluginTest.java` — `getId()` = `parquet-export`; `getSchemaJson()` accepts `{}`; `onActivate` mints credentials and returns `login:password` string (once), returns null when credentials already exist; `getSupportedEvents()` empty (no batch-event processing); registers in `PluginRegistry` (constructor-injection smoke test).
  - Implement: `src/main/java/com/bitbi/dfm/plugin/application/ParquetExportPlugin.java` (`@Component`, `@ConditionalOnProperty("plugins.parquet-export.enabled", matchIfMissing = true)`, pattern: `BitBiPlugin` minus SQL machinery).
  - Commit: `feat(plugin): ParquetExportPlugin SPI implementation (T005)`

- [X] **T006** [US1] Rotation endpoint (owner API)
  - Tests first: contract test `src/test/java/contract/ParquetExportRotationContractTest.java` (MockMvc, pattern of existing contract tests with test security) — `POST /api/v1/account/plugins/parquet-export/rotate-password` → 200 `{login, password}`; 404 when plugin not active; 401 unauthenticated.
  - Implement: handler in `src/main/java/com/bitbi/dfm/plugin/presentation/AccountPluginsController.java` (or a small dedicated controller if cleaner) delegating to `ParquetExportCredentialsService.rotatePassword`; DTO `src/main/java/com/bitbi/dfm/plugin/presentation/dto/RotatePasswordResponseDto.java`.
  - Commit: `feat(plugin): parquet-export password rotation endpoint (T006)`

---

## Phase 3: Security wiring (blocking for US2/US3)

- [X] **T007** [US2] `ParquetExportBasicAuthFilter`
  - Tests first: `src/test/java/com/bitbi/dfm/plugin/presentation/ParquetExportBasicAuthFilterTest.java` — valid `Authorization: Basic` header → SecurityContext holds token with `ROLE_PLUGIN_CLIENT`, principal accountId, detail accountPluginId; missing header / bad base64 / no colon / wrong password → 401 JSON with `WWW-Authenticate: Basic realm="parquet-export"`, context untouched; `shouldNotFilter` for `/download/**` path.
  - Implement: `src/main/java/com/bitbi/dfm/plugin/presentation/ParquetExportBasicAuthFilter.java` (pattern: `PluginApiKeyAuthenticationFilter`, delegates to `ParquetExportCredentialsService.validate`).
  - Commit: `feat(plugin): Basic Auth filter for parquet-export API (T007)`

- [X] **T008** [US2] Security filter chains (prod + test mirror)
  - Tests first: extend `src/test/java/com/bitbi/dfm/security/SecurityFilterChainTest.java` — `/api/v1/plugins/parquet-export/files` routes to the new chain (401 without Basic, never Auth0); `/api/v1/plugins/parquet-export/download/x` is permitAll at chain level (reaches controller → 404 for unknown token); admin catch-all denies the prefix.
  - Implement: new ordered chain in `src/main/java/com/bitbi/dfm/shared/config/SecurityConfiguration.java` (matcher `/api/v1/plugins/parquet-export/**`; `/files` authenticated via filter, `/download/**` permitAll; stateless, CSRF off); `denyAll` carve-out in `adminApiFilterChain`; mirror in `src/test/java/com/bitbi/dfm/config/TestSecurityConfig.java`; route constant in `src/main/java/com/bitbi/dfm/shared/presentation/ApiRoutes.java`.
  - Commit: `feat(security): parquet-export filter chain (T008)`

---

## Phase 4: User Story 2 — Listing + link registration (P1)

- [X] **T009** [US2] `ParquetExportFileService` (catalog derivation)
  - Tests first: `src/test/java/com/bitbi/dfm/plugin/application/ParquetExportFileServiceTest.java` (Mockito over `ChangelogSegmentRepository`, `CheckpointRepository`/their finders, `SiteRepository`, `S3CheckpointStorage`) — delta files derived from egressed segments (`egress_at > since`, stats keys → per-table fan-out, `deltaExists` probe drops missing ones, filename `{table}_seq{first}-{last}.parquet`, producedAt = egressAt); checkpoint files from `s3_key_parquet != null AND updated_at > since` (filename `{table}_seq{seq}.parquet`); account scoping (foreign site never returned; `siteId` filter of a non-owned site → empty); `table`/`type` filters; ordering `(producedAt, id)`; pagination with `size ≤ 100` + `hasMore`.
  - Implement: `src/main/java/com/bitbi/dfm/plugin/application/ParquetExportFileService.java`; add the needed read-only finders (e.g. `findEgressedSince(accountId, since, siteId)`) to `src/main/java/com/bitbi/dfm/delta/domain/ChangelogSegmentRepository.java` + `CheckpointRepository` and their Jpa implementations (JOIN `sites` for account scoping).
  - Commit: `feat(plugin): parquet-export file catalog service (T009)`

- [X] **T010** [US2] `DownloadLinkService` (registration + consume + presign)
  - Tests first: `src/test/java/com/bitbi/dfm/plugin/application/DownloadLinkServiceTest.java` — `registerLinks(accountPluginId, files)` batch-saves one link per file with TTL from config and returns token→file mapping; `consume(token)`: repo `consume` returns 1 → presigned URL minted with 60 s duration + `LINK_CONSUMED` audit; returns 0 + row exists → `LinkGoneException` + `LINK_REJECTED` audit; row absent → `LinkNotFoundException`; presigner never called on failure paths.
  - Implement: `src/main/java/com/bitbi/dfm/plugin/application/DownloadLinkService.java`; config binding `src/main/java/com/bitbi/dfm/plugin/infrastructure/ParquetExportProperties.java` (`plugin.parquet-export.*`: link-ttl-seconds 3600, presign-ttl-seconds 60, purge-retention-days 7, purge-interval-ms 3600000, base-url) + defaults in `src/main/resources/application.yml`.
  - Commit: `feat(plugin): one-time download link service (T010)`

- [X] **T011** [US2] `GET /files` endpoint (controller + rate limiting + audit)
  - Tests first: contract test `src/test/java/contract/ParquetExportFilesContractTest.java` (MockMvc + mocked services, Basic Auth via test chain) — 200 shape per contracts/parquet-export-api.md (files with metadata + absolute `downloadUrl` + `linkExpiresAt`, page/size/hasMore); 400 malformed `since`/`type`/size>100; 401 without/with-bad Basic; 429 with `Retry-After` when rate limiter rejects; `FILES_LISTED` audit recorded with filters + count.
  - Implement: `src/main/java/com/bitbi/dfm/plugin/presentation/ParquetExportApiController.java` (`GET /files`), DTOs `src/main/java/com/bitbi/dfm/plugin/presentation/dto/ParquetFileResponseDto.java` + `ParquetFileListResponseDto.java`; per-account rate limiting reusing `PluginRateLimiterService`; download URL built from `base-url` config or request context.
  - Commit: `feat(plugin): parquet-export file listing endpoint (T011)`

---

## Phase 5: User Story 3 — One-time download (P1)

- [X] **T012** [US3] `GET /download/{token}` endpoint
  - Tests first: contract test `src/test/java/contract/ParquetExportDownloadContractTest.java` — fresh link → 302 with `Location` = presigned URL, no auth required; consumed/expired/inactive-activation → 410; unknown token → 404; response bodies use the standard error DTO; no `WWW-Authenticate` on this route.
  - Implement: handler in `ParquetExportApiController` delegating to `DownloadLinkService.consume`; exception mapping (`LinkGoneException` → 410, `LinkNotFoundException` → 404) in the controller or `GlobalExceptionHandler`.
  - Commit: `feat(plugin): one-time download redirect endpoint (T012)`

---

## Phase 6: User Story 4 — Housekeeping (P2)

- [X] **T013** [US4] `DownloadLinkPurgeScheduler`
  - Tests first: `src/test/java/com/bitbi/dfm/plugin/application/DownloadLinkPurgeSchedulerTest.java` — invokes `repository.purge(now - retentionDays)`; logs deleted count; respects configured retention.
  - Implement: `src/main/java/com/bitbi/dfm/plugin/application/DownloadLinkPurgeScheduler.java` (`@Scheduled(fixedDelayString = "${plugin.parquet-export.purge-interval-ms:3600000}")`).
  - Commit: `feat(plugin): download link purge scheduler (T013)`

---

## Phase 7: Integration & docs (before PR)

- [X] **T014** Integration test (Testcontainers PostgreSQL + LocalStack)
  - `src/test/java/integration/ParquetExportIntegrationTest.java` — end-to-end per quickstart.md: activate (credentials once, hash stored) → seed site + egressed segment + checkpoint + real S3 objects in LocalStack → list with Basic Auth (rows in `download_links`) → follow link (302, S3 URL fetches bytes) → second follow 410 → **concurrency race**: N threads on one token, exactly one 302 → deactivate → listing 401 + unconsumed link 410 → purge deletes aged rows. Cross-account isolation: second account sees nothing.
  - Gate: `./gradlew integrationTest` green.
  - Commit: `test(plugin): parquet-export end-to-end integration suite (T014)`

- [X] **T015** Documentation
  - `docs/parquet-export-plugin-guide.md` — activation, credential handling (shown once, rotation), listing filters + incremental `since` pattern, one-time link semantics (302/410/404, TTLs), config properties, curl walkthrough from quickstart.md. Update `CLAUDE.md` (Recent Changes: 028; migration counter → V39 applied, next V40; plugin endpoints table).
  - Commit: `docs(plugin): parquet-export plugin guide (T015)`

---

## Dependencies

Strictly serial T001 → T015 (repo WIP=1 policy). Notable hard edges: T004 needs T002; T005 needs T004; T007 needs T004; T008 needs T007; T011 needs T008+T009+T010; T012 needs T008+T010; T014 needs everything prior.

## Parallel execution

None — repo policy mandates serial task execution (one commit per task, per-task green gate).

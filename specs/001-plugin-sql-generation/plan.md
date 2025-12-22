# Implementation Plan: Plugin SQL Generation Extension

**Branch**: `001-plugin-sql-generation` | **Date**: 2025-12-22 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/001-plugin-sql-generation/spec.md`
**Extends**: PRD-013 (Plugin System & Bit BI OAuth Integration)

## Summary

Extend the existing Bit BI plugin to automatically generate PostgreSQL SQL files (INSERT, UPDATE, DELETE statements) when batch uploads complete. The system compares CSV files between consecutive batches for the same site, produces SQL reflecting row differences, stores generated files in S3, and provides a Plugin API for Bit BI users to retrieve SQL changes. **Development follows TDD methodology (MANDATORY)**.

## Technical Context

**Language/Version**: Java 21 (LTS) with Spring Boot 3.5.6
**Primary Dependencies**: Spring Boot 3.5.6, Spring Security 6 (OAuth2 Resource Server + Auth0), Spring Data JPA, Spring Events (ApplicationEventPublisher), Hypersistence Utils (JSONB), Apache Commons CSV 1.12.0, java-diff-utils 4.12, ICU4J 76.1
**Storage**: PostgreSQL 16 (new table: `plugin_sql_generations`), AWS S3 (SQL file storage at `plugins/bit-bi/{accountId}/{siteName}/{datetime}.sql`)
**Testing**: JUnit 5 + Mockito (unit), Testcontainers + MockMvc (integration/contract), TDD mandatory
**Target Platform**: Linux server (Docker), AWS ECS
**Project Type**: Web application (backend-only API extension, no frontend)
**Performance Goals**: SQL generation <60s for 100 CSV files (SC-001), API response <2s (SC-002), API Key validation <50ms (SC-004)
**Constraints**: Streaming CSV processing for large files (>100MB), 100% accuracy on row changes (SC-003)
**Scale/Scope**: Multi-tenant SaaS, ~100 accounts, ~500 sites, ~10 batches/day per account

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| TDD Mandatory | ✅ COMPLIANT | All development follows TDD: Contract tests first → Integration tests → Unit tests → Implementation |
| DDD Architecture | ✅ COMPLIANT | Uses existing plugin domain structure (Package by Layered Feature) |
| Library-First | ✅ COMPLIANT | Extends existing plugin infrastructure without new external libraries |
| Security | ✅ COMPLIANT | API Key authentication, siteId ownership verification, no SQL injection risks |
| Observability | ✅ COMPLIANT | Micrometer metrics, structured logging with MDC context |

**Pre-Phase 0 Gate**: PASSED - No violations

## Project Structure

### Documentation (this feature)

```text
specs/001-plugin-sql-generation/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output (OpenAPI)
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (repository root)

```text
src/main/java/com/bitbi/dfm/plugin/
├── domain/
│   ├── Plugin.java                    # (existing) Plugin interface
│   ├── PluginSqlGeneration.java       # NEW: Entity for SQL generation tracking
│   └── PluginSqlGenerationRepository.java  # NEW: Repository interface
├── application/
│   ├── BitBiPlugin.java               # (existing) Extend with SQL generation
│   ├── SqlGenerationService.java      # NEW: Core SQL generation orchestration
│   ├── CsvDiffService.java            # NEW: CSV row-level diff logic
│   ├── SqlStatementGenerator.java     # NEW: SQL statement formatting
│   └── PluginApiKeyService.java       # NEW: API Key generation & validation
├── infrastructure/
│   ├── persistence/
│   │   └── JpaPluginSqlGenerationRepository.java  # NEW
│   └── storage/
│       └── S3SqlFileStorageService.java           # NEW: S3 operations for SQL files
└── presentation/
    ├── BitBiPluginApiController.java  # NEW: Plugin API endpoints
    └── dto/
        ├── SqlChangesResponseDto.java # NEW
        └── SiteListResponseDto.java   # NEW

src/test/java/com/bitbi/dfm/plugin/
├── contract/
│   └── BitBiPluginApiContractTest.java  # NEW: API contract tests (TDD first)
├── integration/
│   ├── SqlGenerationIntegrationTest.java  # NEW
│   └── PluginApiKeyIntegrationTest.java   # NEW
└── unit/
    ├── CsvDiffServiceTest.java            # NEW
    └── SqlStatementGeneratorTest.java     # NEW

src/main/resources/db/migration/
└── V11__create_plugin_sql_generations_table.sql  # NEW
```

**Structure Decision**: Extends existing `plugin` package following DDD/PbLF pattern. No new packages created, all code lives within `com.bitbi.dfm.plugin`.

## Complexity Tracking

> **No constitution violations. This section is empty.**

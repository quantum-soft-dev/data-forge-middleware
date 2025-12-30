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
        ├── SiteListResponseDto.java   # NEW
        ├── TableDto.java              # NEW: Table info with name, size, timestamp
        └── TableListResponseDto.java  # NEW: Response wrapper for /tables

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

## Implementation Details

### CsvDiffService Algorithm

The CSV diff algorithm uses the existing `DiffService` (java-diff-utils with Myers algorithm) to compare CSV files between batches. This approach ensures accurate detection of row-level changes without false positives.

#### Algorithm Steps

1. **Sort CSV Content**: Both previous and current CSV files are sorted by all columns (lexicographic comparison) to normalize row order. This ensures consistent comparison regardless of how the client exports data.

2. **Generate Diff**: Uses `DiffService.generateDiff()` with sorted CSV content (without header row) to produce a JSON diff with hunks containing ADDED/REMOVED changes.

3. **Parse Diff Output**: Convert diff hunks to `CsvRowDiff` objects:
   - **ADDED only** → New row → Generate `INSERT`
   - **REMOVED only** → Deleted row → Generate `DELETE`
   - **Adjacent REMOVED+ADDED with SOME unchanged columns** → Modified row → Generate `UPDATE`
   - **Adjacent REMOVED+ADDED with ALL columns changed** → Different rows → Generate `DELETE` + `INSERT`

#### Key Design Decisions

- **Why Myers Algorithm**: Efficient O(ND) diff algorithm already implemented in codebase via java-diff-utils
- **Why Pre-Sort**: Eliminates false positives from row reordering (e.g., database exports in different order)
- **Why Check Column Changes**: Distinguishes true modifications (some columns unchanged) from unrelated delete+insert (all columns changed)

#### Code Location

- `CsvDiffService.java`: Main diff service using DiffService
- `DiffService.java` / `DiffServiceImpl.java`: Existing Myers algorithm implementation in `comparison` domain

### /tables Endpoint

New endpoint added to support table discovery for Bit BI integration.

#### Query (Native SQL)

```sql
WITH latest_uploads AS (
    SELECT uf.original_file_name, MAX(uf.uploaded_at) AS max_uploaded_at
    FROM uploaded_files uf
    JOIN batches b ON uf.batch_id = b.id
    WHERE b.account_id = :accountId
    GROUP BY uf.original_file_name
)
SELECT uf.original_file_name AS originalFileName,
       uf.file_size AS fileSize,
       uf.uploaded_at AS uploadedAt
FROM uploaded_files uf
JOIN latest_uploads lu ON uf.original_file_name = lu.original_file_name
    AND uf.uploaded_at = lu.max_uploaded_at
JOIN batches b ON uf.batch_id = b.id
WHERE b.account_id = :accountId
ORDER BY uf.original_file_name
```

#### Table Name Derivation

Table names are derived from CSV filenames by:
1. Removing `.csv.gz` extension (if present)
2. Removing `.csv` extension (if present)
3. Prefixing with `_` if name starts with a digit

## Complexity Tracking

> **No constitution violations. This section is empty.**

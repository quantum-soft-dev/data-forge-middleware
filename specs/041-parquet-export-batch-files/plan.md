# Implementation Plan: 041 Parquet Export batch files

**Branch**: `feature/041-parquet-export-batch-files` | **Date**: 2026-08-14 | **Spec**: [spec.md](./spec.md)
**Issue**: #109

## Summary

Expose completed-batch Parquet artifacts through the existing Parquet Export catalog. Add
`type=batch`, make it the unversioned default, show `ABANDONED` without a download URL, persist
`first_seq`/`last_seq` on publish (V51), and keep `type=delta` / `type=checkpoint` as explicit
opt-in to the old sources.

## Technical Context

**Language/Version**: Java 25, Spring Boot 3.5.6
**Primary Dependencies**: Spring Data JPA, NamedParameterJdbcTemplate, Flyway 11
**Storage**: PostgreSQL 16 (`batch_parquet_artifacts` + existing catalog sources)
**Testing**: JUnit 5, Mockito, MockMvc contract tests, Testcontainers integration
**Target Platform**: middleware JVM service
**Project Type**: existing layered-feature backend (`plugin/`, `delta/`)
**Performance Goals**: listing stays bounded to `size+1` SQL rows; no S3 HEAD for batch rows
**Constraints**: no proto/gRPC change; no owner/admin route change; no S3 key change
**Scale/Scope**: one new catalog source, one migration, DTO additions, breaking default

## Constitution Check

CLAUDE.md / AGENTS.md (constitution template unused):

- Feature branch off `develop`, spec + plan + tasks: yes
- TDD, WIP=1, one atomic commit per task: yes
- Forward-only Flyway, next number from the directory (V51): yes
- Strangler: extend the existing plugin listing; do not fork the plugin: yes
- Docs in the same PR: plugin guide + Recent Changes + migration pointers

## Project Structure

```text
specs/041-parquet-export-batch-files/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── contracts/parquet-export-files.md
└── tasks.md

src/main/resources/db/migration/V51__batch_parquet_artifact_seq_range.sql
src/main/java/com/bitbi/dfm/delta/domain/BatchParquetArtifact.java
src/main/java/com/bitbi/dfm/delta/application/BatchParquetFinalizationService.java
src/main/java/com/bitbi/dfm/plugin/infrastructure/ParquetExportCatalogDao.java
src/main/java/com/bitbi/dfm/plugin/application/ParquetExportFileService.java
src/main/java/com/bitbi/dfm/plugin/application/DownloadLinkService.java
src/main/java/com/bitbi/dfm/plugin/presentation/ParquetExportApiController.java
src/main/java/com/bitbi/dfm/plugin/presentation/dto/ParquetFileResponseDto.java
```

## Implementation approach

1. **V51 + aggregate.** Nullable `first_seq`/`last_seq` on `batch_parquet_artifacts`.
   `markReady` records the range; `clearPublishedMetadata` (fail / abandon / requeue) clears it.
   Index `(site_id, ready_at, s3_key)` as specified — READY listings use it; ABANDONED rows have
   null `ready_at` and are rare.
2. **Writer.** On successful publish, compute min/max seq from the batch's published segments
   that mention the table in `stats`, else the batch-wide published range. Pass both into
   `markReady`.
3. **Catalog.** New `findBatchFiles` over `READY`/`ABANDONED`, account-scoped via `sites`.
   `produced_at = CASE READY THEN ready_at ELSE updated_at END`. Cursor key =
   `COALESCE(s3_key, 'abandoned/' || id)`. NULL seq columns coalesce to the batch-wide
   published range. No S3 existence probe for batch rows.
4. **Service default.** `FileType.BATCH`. `listFiles(..., type=null)` is not used by the
   controller anymore: omitted `type` parses to `BATCH`. Explicit `DELTA`/`CHECKPOINT` keep
   today's single-source queries.
5. **HTTP.** New DTO fields `batchId`, `status`. Abandoned items do not call
   `DownloadLink.register`. Filename `{table}_batch{batchId}.parquet`.
6. **Tests / docs.** See tasks.md. Breaking-default callouts in the plugin guide and Recent
   Changes, including one migration sentence for live clients (`type=delta`).

## Compatibility

- **Breaking**: clients that omit `type` stop seeing segment and checkpoint files.
- **Additive**: `type=batch`, `batchId`, `status`.
- **Unchanged**: Basic Auth, cursor, `since`, `size`, download consume, rate limit, owner routes.

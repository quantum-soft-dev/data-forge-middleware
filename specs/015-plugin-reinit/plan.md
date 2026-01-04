# Implementation Plan: BitBi Plugin Reinit Option

**Branch**: `015-plugin-reinit` | **Date**: 2026-01-04 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/015-plugin-reinit/spec.md`

## Summary

Add two new capabilities to the BitBi plugin:
1. **Automatic initialization on activation**: When the plugin is activated or reactivated, automatically trigger SQL generation for the most recent completed batch (if any exist).
2. **Reinit endpoint**: New endpoint that clears all SQL generation history and regenerates from the latest batch, preserving the API key and plugin configuration.

Technical approach: Extend `BitBiPlugin.onActivate()` to check for existing batches and trigger async SQL generation. Add new `reinit()` method to `PluginHistoryService` and expose via `AccountPluginsController`.

## Technical Context

**Language/Version**: Java 21 (LTS)
**Primary Dependencies**: Spring Boot 3.5.6, Spring Security 6 (Auth0 OAuth2), Spring Data JPA, AWS SDK v2 (S3)
**Storage**: PostgreSQL 16 (existing `plugin_sql_generations`, `account_plugins`, `plugin_audit_logs` tables)
**Testing**: JUnit 5 + Mockito + Testcontainers (PostgreSQL + LocalStack S3)
**Target Platform**: Linux server (Docker/ECS)
**Project Type**: Web application (backend-only for this feature)
**Performance Goals**: SQL generation within 60 seconds of activation/reinit
**Constraints**: Async SQL generation to avoid blocking activation response
**Scale/Scope**: Existing plugin system handles ~100 concurrent accounts

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

The constitution file contains placeholder templates without specific gates defined. Proceeding with standard best practices:

- [x] No new external dependencies required
- [x] Follows existing DDD patterns (domain/application/presentation layers)
- [x] Uses existing repository interfaces
- [x] Maintains transactional boundaries
- [x] Async operations use existing Spring patterns

## Project Structure

### Documentation (this feature)

```text
specs/015-plugin-reinit/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── reinit-api.yaml  # OpenAPI contract for reinit endpoint
└── tasks.md             # Phase 2 output (created by /speckit.tasks)
```

### Source Code (repository root)

```text
src/main/java/com/bitbi/dfm/
├── plugin/
│   ├── application/
│   │   ├── BitBiPlugin.java              # MODIFY: Add batch lookup + async init
│   │   ├── PluginActivationService.java  # MODIFY: Signal new vs reactivation
│   │   ├── PluginHistoryService.java     # MODIFY: Add reinit() method
│   │   └── SqlGenerationService.java     # REUSE: generateSqlForBatch()
│   ├── domain/
│   │   ├── PluginActionType.java         # MODIFY: Add REINIT action type
│   │   └── AccountPlugin.java            # READ: isActive() check
│   └── presentation/
│       └── AccountPluginsController.java # MODIFY: Add reinit endpoint
├── batch/
│   └── domain/
│       └── BatchRepository.java          # MODIFY: Add findLatestCompletedByAccountId()

src/test/java/com/bitbi/dfm/plugin/
├── unit/
│   ├── BitBiPluginTest.java              # ADD: Test initialization on activate
│   └── PluginHistoryServiceTest.java     # ADD: Test reinit()
├── integration/
│   └── PluginReinitIntegrationTest.java  # ADD: End-to-end reinit test
└── contract/
    └── AccountPluginsControllerContractTest.java  # ADD: Reinit endpoint contract
```

**Structure Decision**: Backend-only changes following existing DDD package structure. No frontend changes required as reinit can be triggered via API.

## Complexity Tracking

No constitution violations identified. Implementation follows existing patterns:
- Async SQL generation (existing pattern in `BatchEventListener`)
- Batch deletion from `PluginHistoryService.clearHistory()` (reuse)
- Repository pattern for batch queries (existing)

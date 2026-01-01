# Implementation Plan: Plugin History Management

**Branch**: `014-plugin-history` | **Date**: 2026-01-01 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/014-plugin-history/spec.md`

## Summary

Administrative functionality for viewing, clearing, and regenerating plugin SQL generation history. Extends existing plugin admin endpoints with three capabilities: (1) paginated SQL generation history with inline preview and download, (2) complete history clearing with S3 cleanup and plugin deactivation, (3) batch-level SQL regeneration preserving audit trail.

## Technical Context

**Language/Version**: Java 21 (LTS)
**Primary Dependencies**: Spring Boot 3.5.6, Spring Security 6 (Auth0 OAuth2), Spring Data JPA, AWS SDK v2 (S3)
**Storage**: PostgreSQL 16 (existing plugin_sql_generations, account_plugins, plugin_audit_logs tables)
**Testing**: JUnit 5 + Mockito + Testcontainers (PostgreSQL + LocalStack S3)
**Target Platform**: Linux server (Docker/ECS)
**Project Type**: Web application (backend API + frontend UI)
**Performance Goals**: 2s page load for history view, 30s for clear operation (<1000 generations)
**Constraints**: ROLE_ADMIN required, 100 statements per page pagination
**Scale/Scope**: Typical account <1000 generations, files up to 50MB

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

The constitution template is not yet customized for this project. Proceeding with standard gates:

| Gate | Status | Notes |
|------|--------|-------|
| Follows existing patterns | ✅ Pass | Uses existing PluginAdminController pattern, DDD structure |
| Test-first approach | ✅ Pass | Contract + integration tests planned |
| Security compliance | ✅ Pass | ROLE_ADMIN required, audit logging |
| No unnecessary complexity | ✅ Pass | Extends existing entities, no new services beyond scope |

## Project Structure

### Documentation (this feature)

```text
specs/014-plugin-history/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output (OpenAPI specs)
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (repository root)

```text
# Backend (existing structure, new files marked with *)
src/main/java/com/bitbi/dfm/plugin/
├── application/
│   ├── SqlGenerationService.java        # Extend for regeneration
│   ├── PluginHistoryService.java*       # New: history view/clear operations
│   └── PluginAuditService.java          # Extend with new action types
├── domain/
│   ├── PluginSqlGeneration.java         # Add superseded flag/reference
│   ├── PluginSqlGenerationRepository.java  # Add query methods
│   └── ActionType.java                  # Add new action types
├── presentation/
│   └── PluginAdminController.java       # Extend with new endpoints
└── infrastructure/
    ├── storage/
    │   └── S3SqlFileStorageService.java # Add bulk delete method
    └── persistence/
        └── JpaPluginSqlGenerationRepository.java  # Implement new queries

src/main/resources/db/migration/
└── V14__add_plugin_history_fields.sql*  # New: superseded fields

# Frontend (existing structure, new files marked with *)
frontend/src/
├── features/plugin-history/*            # New feature
│   ├── api/
│   │   ├── plugin-history.api.ts*
│   │   └── plugin-history.queries.ts*
│   ├── model/
│   │   └── types.ts*
│   └── ui/
│       ├── SqlGenerationList.tsx*
│       ├── SqlPreview.tsx*
│       ├── ClearHistoryDialog.tsx*
│       └── RegenerateButton.tsx*
├── widgets/plugin-history/*
│   └── PluginHistoryWidget.tsx*
└── pages/admin/
    └── PluginHistoryPage.tsx*

# Tests
src/test/java/com/bitbi/dfm/plugin/
├── contract/
│   └── PluginHistoryAdminControllerTest.java*
├── integration/
│   └── PluginHistoryIntegrationTest.java*
└── unit/
    └── PluginHistoryServiceTest.java*

frontend/src/features/plugin-history/__tests__/
└── PluginHistoryWidget.test.tsx*
```

**Structure Decision**: Extends existing plugin module following DDD package-by-feature pattern. New PluginHistoryService orchestrates view/clear/regenerate operations. Frontend adds new feature slice for admin plugin history management.

## Complexity Tracking

No constitution violations. Feature follows established patterns:
- Extends existing PluginAdminController (no new controller)
- Uses existing repository pattern
- Follows existing entity modification approach (add fields, not new tables)
- Uses established S3 storage service

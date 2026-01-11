# Implementation Plan: Global Error Handling

**Branch**: `016-global-error-handling` | **Date**: 2026-01-11 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/016-global-error-handling/spec.md`

## Summary

Расширение существующей системы логирования ошибок для поддержки глобальных ошибок (не связанных с batch). Добавление полей `severity` и `is_read` в таблицу `error_logs`, создание User API для просмотра и управления статусом прочтения, Dashboard виджет для отображения глобальных ошибок с badge счётчиком непрочитанных.

## Technical Context

**Language/Version**: Java 21 (LTS)
**Primary Dependencies**: Spring Boot 3.5.6, Spring Security 6 (Auth0 OAuth2), Spring Data JPA
**Storage**: PostgreSQL 16 (partitioned `error_logs` table), Flyway 11
**Testing**: JUnit 5 + Mockito + Testcontainers (PostgreSQL)
**Target Platform**: Linux server (Docker), Web browser (React SPA)
**Project Type**: Web application (backend + frontend)
**Performance Goals**: <2s error submission, <3s bulk mark-as-read (100 items)
**Constraints**: Backwards-compatible migration, existing partitioning preserved
**Scale/Scope**: 10,000+ errors per account, pagination 20-50 items

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Constitution не настроен (шаблон по умолчанию). Применяем стандартные практики проекта из CLAUDE.md:
- ✅ DDD layered architecture (error domain exists)
- ✅ Repository pattern (ErrorLogRepository interface)
- ✅ Records for DTOs
- ✅ Testcontainers for integration tests
- ✅ Flyway migrations
- ✅ OpenAPI documentation

## Project Structure

### Documentation (this feature)

```text
specs/016-global-error-handling/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output (OpenAPI)
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
# Backend (Java/Spring Boot)
src/main/java/com/bitbi/dfm/error/
├── domain/
│   ├── ErrorLog.java              # MODIFY: add severity, isRead fields
│   ├── ErrorLogRepository.java    # MODIFY: add global error queries
│   └── ErrorSeverity.java         # NEW: enum (CRITICAL, ERROR, WARNING, INFO)
├── application/
│   └── ErrorLoggingService.java   # MODIFY: add global error methods
└── presentation/
    ├── ErrorLogController.java         # MODIFY: add severity to request
    ├── GlobalErrorUserController.java  # NEW: user-facing API
    └── dto/
        ├── LogErrorRequestDto.java        # MODIFY: add severity field
        ├── GlobalErrorResponseDto.java    # NEW: includes isRead
        └── MarkAsReadRequestDto.java      # NEW: bulk mark request

src/main/resources/db/migration/
└── V17__add_severity_and_is_read_to_error_logs.sql  # NEW: migration

src/test/java/com/bitbi/dfm/error/
├── integration/
│   └── GlobalErrorIntegrationTest.java  # NEW
└── contract/
    └── GlobalErrorUserControllerTest.java  # NEW

# Frontend (React/TypeScript)
frontend/src/
├── features/global-errors/
│   ├── api/
│   │   ├── global-errors.api.ts      # NEW: API client
│   │   └── global-errors.queries.ts  # NEW: TanStack Query hooks
│   ├── model/
│   │   └── global-error.types.ts     # NEW: TypeScript types
│   └── ui/
│       ├── GlobalErrorList.tsx       # NEW: error list component
│       ├── GlobalErrorItem.tsx       # NEW: single error row
│       └── GlobalErrorDetails.tsx    # NEW: error detail modal
└── widgets/global-errors/
    └── GlobalErrorsWidget.tsx        # NEW: Dashboard widget with badge
```

**Structure Decision**: Web application structure - extending existing backend `error/` domain and adding new frontend feature slice `global-errors/`. Dashboard виджет в `widgets/global-errors/`.

## Complexity Tracking

> No violations - extending existing error domain with minimal new patterns

| Aspect | Decision | Rationale |
|--------|----------|-----------|
| Storage | Extend error_logs | Reuse partitioning, indexes, existing code |
| Migration | Add columns with defaults | Backwards-compatible |
| API | New UserController | Separate from existing admin/client APIs |

# Implementation Plan: Admin Controllers DTO Refactoring and Error Handling Standardization

**Branch**: `004-code-improvements-the` | **Date**: 2025-10-11 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/004-code-improvements-the/spec.md`

## Summary

This feature refactors admin API controllers to replace unstructured `Map<String, Object>` parameters and responses with type-safe DTO records, eliminates controller-level try-catch blocks in favor of centralized exception handling via `GlobalExceptionHandler`, and updates OpenAPI documentation to accurately reflect the new API contracts. The refactoring targets `AccountAdminController`, `SiteAdminController`, `ErrorAdminController`, `BatchAdminController`, and `ErrorLogController` to improve type safety, API reliability, and developer experience.

**Technical Approach**: Define request DTOs (CreateAccountRequestDto, UpdateAccountRequestDto, CreateSiteRequestDto, UpdateSiteRequestDto, LogErrorRequestDto) and response DTOs (AccountWithStatsResponseDto, SiteCreationResponseDto, BatchSummaryDto, BatchDetailResponseDto, UploadedFileDto, ErrorLogSummaryDto, AccountStatisticsDto, SiteStatisticsDto) as Java records with validation annotations. Update `GlobalExceptionHandler` to handle custom service exceptions (AccountNotFoundException, SiteNotFoundException, etc.) and return standardized `ErrorResponseDto`. Remove try-catch blocks from controllers and let exceptions propagate to the global handler. Add OpenAPI annotations to ensure proper schema generation.

## Technical Context

**Language/Version**: Java 21 (LTS)
**Primary Dependencies**: Spring Boot 3.5.6, Spring Web, Spring Data JPA, Jakarta Validation (Bean Validation 3.0), SpringDoc OpenAPI 3
**Storage**: PostgreSQL 16 (existing schema, no changes)
**Testing**: JUnit 5, Mockito, Spring MockMvc (contract tests), Testcontainers (integration tests)
**Target Platform**: Linux server (existing deployment infrastructure)
**Project Type**: Web backend (Spring Boot REST API)
**Performance Goals**: Maintain existing <1000ms p95 latency for API endpoints
**Constraints**: Zero downtime deployment (DTOs must be backward-compatible during rollout), maintain 80% code coverage threshold
**Scale/Scope**: 5 controllers refactored, 13 new DTO records created, 8 exception handlers added to GlobalExceptionHandler, ~50 test files updated

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### Backend Principles Compliance

✅ **Principle I (DDD)**: Controllers use DTOs for API boundaries, domain entities remain unchanged in domain layer

✅ **Principle II (PbLF)**: DTOs placed in presentation layer (e.g., `account/presentation/dto/`), existing package structure preserved

✅ **Principle III (TDD - NON-NEGOTIABLE)**: Contract tests → Integration tests → Implementation → Unit tests workflow will be followed. All tests must pass before task completion.

✅ **Principle IV (API-First Design)**: OpenAPI annotations ensure contract-first approach, existing REST endpoints preserved

✅ **Principle V (Security by Default)**: No security changes, existing JWT/Keycloak authentication preserved

✅ **Principle VI (Database Optimization)**: No database changes, existing queries unaffected

✅ **Principle VII (Observability)**: No logging/metrics changes, existing observability preserved

### Code Quality Requirements

✅ **Java 21 features**: Records used for DTOs (immutable by default)

✅ **No circular dependencies**: DTOs are leaf nodes in dependency graph

✅ **DTOs for API request/response**: Core purpose of this feature

✅ **Explicit repository methods**: No changes to repositories

### Testing Requirements

✅ **Contract tests with MockMvc**: Will update existing contract tests for new DTO structures

✅ **Integration tests with Testcontainers**: Will update existing integration tests

✅ **Unit tests with Mockito**: Will add unit tests for DTO validation logic

✅ **Coverage ≥80%**: Will verify after refactoring

### Implementation Workflow

✅ **TDD process**: Write tests first, implement to pass, refactor

✅ **Task execution**: Contract test → integration test → implementation → unit test

✅ **Coverage verification**: Check coverage after each controller refactored

### Pull Request Requirements

✅ **All tests passing**: Gate condition for PR approval

✅ **Coverage ≥80%**: Gate condition for PR approval

✅ **API contract updated**: OpenAPI annotations ensure documentation accuracy

✅ **Constitution compliance**: This checklist verifies compliance

**GATE STATUS**: ✅ PASSED - No violations, no complexity tracking needed

## Project Structure

### Documentation (this feature)

```
specs/004-code-improvements-the/
├── spec.md              # Feature specification (created by /speckit.specify)
├── plan.md              # This file (created by /speckit.plan)
├── research.md          # Phase 0 output (created by /speckit.plan)
├── data-model.md        # Phase 1 output (created by /speckit.plan)
├── quickstart.md        # Phase 1 output (created by /speckit.plan)
├── contracts/           # Phase 1 output (created by /speckit.plan)
│   ├── request-dtos.yaml    # OpenAPI schemas for request DTOs
│   └── response-dtos.yaml   # OpenAPI schemas for response DTOs
├── checklists/
│   └── requirements.md  # Spec quality checklist (created by /speckit.specify)
└── tasks.md             # Phase 2 output (created by /speckit.tasks - NOT by /speckit.plan)
```

### Source Code (repository root)

```
src/main/java/com/bitbi/dfm/
├── account/
│   ├── presentation/
│   │   ├── dto/
│   │   │   ├── CreateAccountRequestDto.java          # NEW
│   │   │   ├── UpdateAccountRequestDto.java          # NEW
│   │   │   ├── AccountWithStatsResponseDto.java      # NEW
│   │   │   └── AccountStatisticsDto.java             # NEW
│   │   └── AccountAdminController.java               # MODIFIED
│   └── [domain/application/infrastructure unchanged]
│
├── site/
│   ├── presentation/
│   │   ├── dto/
│   │   │   ├── CreateSiteRequestDto.java             # NEW
│   │   │   ├── UpdateSiteRequestDto.java             # NEW
│   │   │   ├── SiteCreationResponseDto.java          # NEW
│   │   │   └── SiteStatisticsDto.java                # NEW
│   │   └── SiteAdminController.java                  # MODIFIED
│   └── [domain/application/infrastructure unchanged]
│
├── batch/
│   ├── presentation/
│   │   ├── dto/
│   │   │   ├── BatchSummaryDto.java                  # NEW
│   │   │   ├── BatchDetailResponseDto.java           # NEW
│   │   │   └── UploadedFileDto.java                  # NEW
│   │   └── BatchAdminController.java                 # MODIFIED
│   └── [domain/application/infrastructure unchanged]
│
├── error/
│   ├── presentation/
│   │   ├── dto/
│   │   │   ├── LogErrorRequestDto.java               # NEW
│   │   │   └── ErrorLogSummaryDto.java               # NEW
│   │   ├── ErrorLogController.java                   # MODIFIED
│   │   └── ErrorAdminController.java                 # MODIFIED
│   └── [domain/application/infrastructure unchanged]
│
└── shared/
    ├── exception/
    │   └── GlobalExceptionHandler.java               # MODIFIED (add exception handlers)
    └── presentation/dto/
        ├── ErrorResponseDto.java                      # EXISTING (no changes)
        └── PageResponseDto.java                       # EXISTING (no changes)

src/test/java/com/bitbi/dfm/
├── account/presentation/
│   └── AccountAdminControllerTest.java               # MODIFIED
├── site/presentation/
│   └── SiteAdminControllerTest.java                  # MODIFIED
├── batch/presentation/
│   └── BatchAdminControllerTest.java                 # MODIFIED
├── error/presentation/
│   ├── ErrorLogControllerTest.java                   # MODIFIED
│   └── ErrorAdminControllerTest.java                 # MODIFIED
├── contract/
│   └── AdminContractTest.java                        # MODIFIED
└── integration/
    ├── AdminEndpointsIntegrationTest.java            # MODIFIED
    └── [other integration tests]                     # MODIFIED (as needed)
```

**Structure Decision**: This refactoring follows the existing Package by Layered Feature (PbLF) structure. DTOs are added to `presentation/dto/` directories within each feature module (account, site, batch, error). Controllers are modified in place. The `GlobalExceptionHandler` in the shared exception package is extended to handle new custom exceptions. No new directories or modules are created - this is a surgical refactoring of existing controller layer code.

## Complexity Tracking

*No violations detected - this section is empty.*

The feature fully complies with all constitutional principles. No complexity justification required.

## Phase 0: Research

**Status**: Ready to execute

### Research Tasks

1. **Jakarta Validation Annotations Best Practices**
   - **Question**: What validation annotations should be used for request DTOs?
   - **Scope**: @NotNull, @NotBlank, @Email, @Size, custom validators
   - **Output**: Validation strategy document

2. **OpenAPI Schema Generation for Java Records**
   - **Question**: How do SpringDoc OpenAPI annotations work with Java records?
   - **Scope**: @Schema annotations on record components, @ArraySchema, example values
   - **Output**: OpenAPI annotation patterns for DTOs

3. **GlobalExceptionHandler Custom Exception Mapping**
   - **Question**: What HTTP status codes should map to service exceptions?
   - **Scope**: AccountNotFoundException (404), AccountAlreadyExistsException (409), etc.
   - **Output**: Exception-to-HTTP-status mapping table

4. **Backward Compatibility Strategy for DTO Migration**
   - **Question**: How to deploy DTO changes without breaking existing API consumers?
   - **Scope**: JSON serialization compatibility, field naming, optional fields
   - **Output**: Deployment strategy document

5. **Test Strategy for DTO Refactoring**
   - **Question**: How to update contract/integration tests efficiently?
   - **Scope**: Test data builders, JSON assertion patterns, MockMvc DTO serialization
   - **Output**: Test refactoring checklist

### Research Execution

Execute research agents concurrently for all 5 tasks. Consolidate findings into `research.md`.

## Phase 1: Design & Contracts

**Prerequisites**: Phase 0 (research.md) complete

### Deliverables

1. **data-model.md**: All 13 DTO record definitions with field types, validation rules, and relationships
2. **contracts/**: OpenAPI YAML schemas for request and response DTOs
3. **quickstart.md**: Developer guide for using DTOs, validation, and exception handling

### Design Process

1. Extract entity definitions from spec.md (FR-001 through FR-013)
2. Define Java record structures with validation annotations
3. Generate OpenAPI schemas showing request/response examples
4. Document exception handling flow (controller → GlobalExceptionHandler → ErrorResponseDto)
5. Create quickstart guide with code examples

### Agent Context Update

After Phase 1 completion, run:

```bash
.specify/scripts/bash/update-agent-context.sh claude
```

This updates `.claude/settings.local.json` or similar agent context files with new DTO patterns and validation strategies.

## Phase 2: Task Generation

**NOT PART OF THIS COMMAND** - User must run `/speckit.tasks` separately.

The `/speckit.tasks` command will generate `tasks.md` with granular implementation tasks organized by user story priority (P1 → P2 → P3 → P4).

---

**Next Step**: Execute Phase 0 research agents to populate `research.md`.

# Implementation Plan: File Diff Comparison Between Upload Sessions

**Branch**: `009-markdown-user-story` | **Date**: 2025-11-03 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/009-markdown-user-story/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/commands/plan.md` for the execution workflow.

## Summary

This feature enables users to compare files between different upload sessions to track changes over time. Users can select files from a current upload session, choose an earlier session for comparison, and view detailed diff results showing added, removed, and modified content. The system generates comparison results for modified files, identifies new files, skips unchanged files, and produces a comprehensive summary report with statistics. Technical approach involves implementing a diff engine using standard algorithms (Myers diff), storing comparison results as entities linked to upload sessions, providing a visual diff UI component in React, and leveraging existing S3 infrastructure to access file contents.

## Technical Context

**Language/Version**: Backend: Java 21 (LTS), Frontend: TypeScript 5.6 with React 19.2
**Primary Dependencies**:
  - Backend: Spring Boot 3.5.6, Spring Data JPA, AWS SDK v2 (S3)
  - Frontend: TanStack Query v5, TanStack Router, shadcn/ui, Tailwind CSS
  - Diff Library: [NEEDS CLARIFICATION - Java-diff-utils vs google-diff-match-patch vs custom implementation]
  - Frontend Diff Viewer: [NEEDS CLARIFICATION - react-diff-viewer vs monaco-editor vs custom component]

**Storage**: PostgreSQL 16 (new tables: file_comparisons, comparison_results), AWS S3 (file content retrieval)
**Testing**: Backend: JUnit 5, Mockito, Testcontainers (PostgreSQL + LocalStack S3); Frontend: Vitest, React Testing Library
**Target Platform**: Web application (backend services + React SPA)
**Project Type**: Web (full-stack feature spanning backend and frontend)
**Performance Goals**:
  - Comparison generation: up to 100 files within 2 minutes (per spec SC-002)
  - Visual diff editor load: within 3 seconds (per spec SC-003)
  - File selection UI response: under 30 seconds for initial interaction (per spec SC-001)
  - Support up to 1000 files per upload session (per spec SC-007)

**Constraints**:
  - Response time <1000ms p95 for API endpoints (constitution principle VII)
  - Frontend bundle size <500KB gzipped (constitution principle XV)
  - File comparison accuracy: 99.9% for identical file detection (per spec SC-008)
  - No interference with concurrent uploads (per spec SC-009)
  - Memory-efficient streaming for large files (handle 100MB+ files per edge case)

**Scale/Scope**:
  - Support comparison of up to 1000 files per session
  - Handle text files up to 100MB+ in size
  - Store comparison results for lifetime of upload session
  - Multi-account isolation (users only see their own comparisons)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### Backend Principles Compliance

- **✅ I. Domain-Driven Design (DDD)**: Will implement FileComparison as aggregate root with ComparisonResult value objects, domain service for diff logic, application service for workflow orchestration
- **✅ II. Package by Layered Feature (PbLF)**: New `comparison` package with domain/application/infrastructure/presentation layers, self-contained with clear API boundaries
- **✅ III. Test-Driven Development (NON-NEGOTIABLE)**: Will follow TDD workflow: contract tests → integration tests → implementation → unit tests. Red-Green-Refactor cycle enforced. Targeting 80%+ coverage.
- **✅ IV. API-First Design**: Will define OpenAPI 3.0 contracts before implementation. RESTful endpoints with JSON request/response format. Versioned under `/api/v1/`.
- **✅ V. Security by Default**: Comparison operations restricted to same-account sessions (JWT accountId validation). Input validation on all endpoints. Authorization checks before file access.
- **✅ VI. Database Optimization**: New tables with indexes on foreign keys (batch_id, account_id). Flyway migration for schema versioning. Transactions for comparison creation/deletion. No partitioning needed (lower volume than error_logs).
- **✅ VII. Observability & Monitoring**: Will add Micrometer metrics (comparison.started, comparison.completed, comparison.duration). Structured logging with MDC context (comparisonId, batchIds). Health check not needed (no external dependencies beyond existing S3).

### Frontend Principles Compliance

- **✅ VIII. Feature-Sliced Design (FSD Architecture)**: Will create `features/file-comparison/` with entities, features, widgets, pages layers. Bottom-up imports only. Public API via index.ts.
- **✅ IX. Type Safety First (TypeScript Strict Mode)**: All components, hooks, DTOs typed explicitly. Zod schemas for API responses with `z.infer` for type derivation. No `any` types.
- **✅ X. React Query for Server State**: All comparison API calls through TanStack Query hooks (useComparisons, useComparisonDetails). Query key factories for caching hierarchy. Optimistic updates for delete operations.
- **✅ XI. TDD for Frontend (NON-NEGOTIABLE)**: Will write tests before implementation. 70% unit tests (Vitest), 20% integration tests (Testing Library). Targeting 80%+ coverage, 95%+ for critical paths.
- **✅ XII. Keycloak SSO Integration**: Existing Keycloak integration provides authentication. Comparison endpoints use JWT tokens from existing auth flow. No new auth requirements.
- **✅ XIII. Component Composition over Prop Drilling**: Will use TanStack Query for server state, React Context for diff viewer settings (theme, line numbers). Custom hooks for reusable comparison logic.
- **✅ XIV. Form Validation with Zod**: File selection and comparison target forms validated with React Hook Form + Zod. Schema-driven validation before API calls.
- **✅ XV. Performance & Bundle Optimization**: Diff viewer will be lazy-loaded with React.lazy. Virtualization for file lists >100 items using TanStack Table. Debounced search if file filtering needed. Bundle impact estimated <50KB (diff library choice critical).
- **✅ XVI. Accessibility & Security**: Diff viewer will have ARIA labels for change types, keyboard navigation support. React's automatic XSS escaping for diff content. Semantic HTML for file lists.

### Development Standards Compliance

- **✅ Git Workflow Standards**: Using feature branch `009-markdown-user-story` (follows numeric prefix pattern from existing spec scripts)
- **✅ Backend Code Quality Requirements**: Will use Java 21 records for DTOs, Spring Boot 3.5.6 conventions, no circular dependencies, explicit repository query methods, separate DTOs from domain entities
- **✅ Frontend Code Quality Requirements**: PascalCase components, camelCase hooks with `use` prefix, functional components only, named exports, no prohibited anti-patterns
- **✅ Backend Testing Requirements**: Testcontainers for integration tests, MockMvc for contract tests, Mockito for unit tests, <1000ms p95 latency validation
- **✅ Frontend Testing Requirements**: 70/20/10 unit/integration/E2E split, Vitest + Testing Library, test behavior not implementation, 80%+ coverage
- **✅ Security Requirements**: No credentials in code, JWT with account context, Keycloak admin endpoints not needed, S3 IAM roles for file access, XSS prevention via React, tokens in httpOnly cookies

### Implementation Workflow Compliance

- **✅ Backend Task Execution Process**: Will follow TDD process: read contract test spec → write failing test → get approval → implement → refactor → verify coverage → mark completed
- **✅ Frontend Task Execution Process**: Will follow TDD process: create branch → write tests (RED) → implement (GREEN) → refactor → verify coverage → commit → PR
- **✅ Pull Request Requirements**: Will ensure all tests passing, coverage ≥80%, Flyway migration for new tables, API contracts in OpenAPI format, TypeScript strict mode, ESLint 0 errors, bundle size check, accessibility validation

### Gate Evaluation: ✅ PASSED

No constitutional violations detected. All principles can be satisfied without exceptions. Proceeding to Phase 0 research.

---

## Post-Design Constitution Re-evaluation

*Re-evaluated after Phase 0 (Research) and Phase 1 (Design & Contracts) completion.*

### Design Artifacts Review

**Phase 0 Outputs**:
- ✅ research.md: All NEEDS CLARIFICATION items resolved (diff library, diff viewer, large file handling, storage strategy)

**Phase 1 Outputs**:
- ✅ data-model.md: DDD-compliant domain model with FileComparison aggregate, ComparisonResult value objects
- ✅ comparison-api.yaml: OpenAPI 3.0 contract with RESTful endpoints, JWT security, standardized error responses
- ✅ quickstart.md: Developer guide with architecture overview, API reference, development workflow

### Technology Decisions Validation

**Backend**:
- ✅ Diff Library: java-diff-utils 4.12 (Apache 2.0 license, mature, performant)
- ✅ Storage: Unified diff in PostgreSQL JSONB (simple, queryable, sufficient size)
- ✅ Large File Handling: Streaming with 10K line chunks (memory-efficient, meets performance goals)

**Frontend**:
- ✅ Diff Viewer: react-diff-viewer-continued (~20KB gzipped, within 500KB budget)
- ✅ Code Splitting: Lazy load diff viewer with React.lazy()
- ✅ Virtualization: TanStack Table for file lists >100 items

### Constitutional Compliance Post-Design

**Backend Principles**:
- ✅ **I. DDD**: FileComparison aggregate enforces invariants, DiffService domain service, clear boundaries
- ✅ **II. PbLF**: New `comparison` package with domain/application/infrastructure/presentation layers
- ✅ **III. TDD**: Contract tests → Integration tests → Implementation → Unit tests workflow documented
- ✅ **IV. API-First**: OpenAPI 3.0 contract complete (comparison-api.yaml) before implementation
- ✅ **V. Security**: JWT accountId validation, input validation on all endpoints, authorization checks
- ✅ **VI. Database**: Indexes on foreign keys, Flyway migration V009, transactions for state changes
- ✅ **VII. Observability**: Micrometer metrics defined (comparison.duration, comparison.created)

**Frontend Principles**:
- ✅ **VIII. FSD**: Layers defined: app → pages → widgets → features → entities → shared
- ✅ **IX. Type Safety**: TypeScript interfaces, Zod schemas for validation, no `any` types
- ✅ **X. React Query**: TanStack Query hooks (useComparisons, useCreateComparison, useDeleteComparison)
- ✅ **XI. TDD**: Test structure defined (70% unit, 20% integration, 10% E2E)
- ✅ **XII. Keycloak SSO**: Uses existing JWT auth flow, no new auth requirements
- ✅ **XIII. Component Composition**: React Context for diff viewer settings, TanStack Query for server state
- ✅ **XIV. Form Validation**: React Hook Form + Zod for file selection forms
- ✅ **XV. Performance**: Lazy loading, virtualization, debouncing, bundle <20KB for diff viewer
- ✅ **XVI. Accessibility**: ARIA labels planned, keyboard navigation, semantic HTML

**Development Standards**:
- ✅ **Git Workflow**: Using feature branch `009-markdown-user-story`
- ✅ **Code Quality**: Java 21 records for DTOs, PascalCase components, camelCase hooks
- ✅ **Testing**: Testcontainers, MockMvc, Vitest, Testing Library, ≥80% coverage target
- ✅ **Security**: No credentials in code, JWT validation, React XSS prevention

### Implementation Risk Assessment

**Low Risk**:
- ✅ Technology choices are mature and well-documented
- ✅ Similar patterns used in Upload History feature (Spec 008)
- ✅ Clear separation of concerns (DDD + FSD)
- ✅ Comprehensive test strategy defined

**Medium Risk**:
- ⚠️ Large file handling (100MB+) requires careful memory management
  - **Mitigation**: Streaming approach documented, integration tests with large files required
- ⚠️ Diff viewer performance for 10K+ lines
  - **Mitigation**: Virtualization strategy documented, fallback to download if needed

**No High Risks Identified**

### Final Gate Evaluation: ✅ PASSED

All constitutional principles remain satisfied after design phase. No violations introduced. Ready to proceed to Phase 2 (Task Generation via `/speckit.tasks`).

## Project Structure

### Documentation (this feature)

```
specs/[###-feature]/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```
# Backend (Java/Spring Boot)
src/main/java/com/bitbi/dfm/
├── comparison/                      # New domain for this feature (PbLF)
│   ├── domain/
│   │   ├── FileComparison.java      # Aggregate root
│   │   ├── ComparisonResult.java    # Value object for individual file diff
│   │   ├── ComparisonStatus.java    # Enum: PENDING, IN_PROGRESS, COMPLETED, FAILED
│   │   ├── ChangeType.java          # Enum: ADDED, REMOVED, MODIFIED, UNCHANGED
│   │   ├── ComparisonSummary.java   # Value object for summary report
│   │   └── DiffService.java         # Domain service for diff algorithm
│   ├── application/
│   │   ├── ComparisonService.java           # Application service (workflow orchestration)
│   │   ├── ComparisonQueryService.java      # Read-side queries
│   │   └── events/
│   │       ├── ComparisonStartedEvent.java
│   │       └── ComparisonCompletedEvent.java
│   ├── infrastructure/
│   │   ├── JpaComparisonRepository.java     # Repository implementation
│   │   ├── S3FileContentService.java        # Retrieves file content from S3
│   │   └── persistence/
│   │       ├── FileComparisonEntity.java    # JPA entity
│   │       └── ComparisonResultEntity.java  # JPA entity
│   └── presentation/
│       ├── ComparisonController.java        # REST endpoints
│       └── dto/
│           ├── CreateComparisonRequestDto.java
│           ├── ComparisonResponseDto.java
│           ├── ComparisonResultDto.java
│           └── ComparisonSummaryDto.java
├── batch/                           # Existing domain (dependency for file access)
│   └── [existing batch structure]
└── upload/                          # Existing domain (dependency for file metadata)
    └── [existing upload structure]

src/main/resources/
└── db/migration/
    └── V009__create_file_comparison_tables.sql  # Flyway migration

src/test/java/com/bitbi/dfm/comparison/
├── contract/
│   └── ComparisonContractTest.java          # MockMvc contract tests
├── integration/
│   ├── ComparisonIntegrationTest.java       # Testcontainers (PostgreSQL + S3)
│   └── DiffServiceIntegrationTest.java      # Large file handling tests
└── domain/
    ├── FileComparisonTest.java              # Unit tests for aggregate
    └── DiffServiceTest.java                 # Unit tests for diff algorithm

# Frontend (React/TypeScript)
frontend/src/
├── app/                             # FSD: Application layer
│   └── routes/
│       └── comparison/
│           └── $comparisonId.tsx    # Route for viewing comparison details
├── pages/                           # FSD: Pages layer
│   └── comparison/
│       ├── ComparisonPage.tsx       # Main comparison workflow page
│       └── ComparisonDetailPage.tsx # Detailed view of single comparison
├── widgets/                         # FSD: Widgets layer
│   └── comparison/
│       ├── FileSelectionWidget.tsx       # File selection UI
│       ├── ComparisonResultsWidget.tsx   # Results display
│       └── DiffViewerWidget.tsx          # Diff visualization container
├── features/                        # FSD: Features layer
│   └── file-comparison/
│       ├── api/
│       │   └── comparisonApi.ts          # API client functions
│       ├── hooks/
│       │   ├── useComparisons.ts         # TanStack Query hook
│       │   ├── useComparisonDetails.ts   # TanStack Query hook
│       │   ├── useCreateComparison.ts    # TanStack Query mutation
│       │   └── useDeleteComparison.ts    # TanStack Query mutation
│       ├── model/
│       │   ├── types.ts                  # TypeScript interfaces
│       │   └── schemas.ts                # Zod validation schemas
│       └── ui/
│           ├── FileSelector.tsx          # File selection component
│           ├── DiffViewer.tsx            # Diff display component
│           └── ComparisonSummary.tsx     # Summary report component
├── entities/                        # FSD: Entities layer
│   └── comparison/
│       ├── model/
│       │   └── types.ts                  # Domain types (Comparison, ComparisonResult)
│       └── ui/
│           ├── ComparisonCard.tsx        # List item component
│           └── ChangeTypeIndicator.tsx   # Visual indicator for change type
└── shared/                          # FSD: Shared layer
    ├── api/
    │   └── client.ts                # Axios instance (existing)
    └── ui/
        └── [existing shared components]

frontend/src/__tests__/
├── features/file-comparison/
│   ├── hooks/
│   │   ├── useComparisons.test.ts
│   │   ├── useCreateComparison.test.ts
│   │   └── useDeleteComparison.test.ts
│   └── ui/
│       ├── FileSelector.test.tsx
│       ├── DiffViewer.test.tsx
│       └── ComparisonSummary.test.tsx
└── integration/
    └── comparison-workflow.test.tsx     # Full user flow test
```

**Structure Decision**: Web application with backend/frontend separation. Backend follows **Package by Layered Feature (PbLF)** with new `comparison` package under `com.bitbi.dfm.comparison`. Frontend follows **Feature-Sliced Design (FSD)** with layers: app → pages → widgets → features → entities → shared. This structure aligns with constitution principles II (PbLF) and VIII (FSD Architecture) and matches existing codebase patterns established in Upload History feature (Spec 008).

## Complexity Tracking

*Fill ONLY if Constitution Check has violations that must be justified*

**Status**: No violations - this section is intentionally empty.

All constitutional principles can be satisfied without exceptions. No complexity justifications required.

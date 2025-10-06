# Data Forge Middleware Constitution

## Core Principles

### I. Domain-Driven Design (DDD)
All domain logic resides in dedicated domain layer classes (entities, aggregates, value objects, domain services). Entities enforce business invariants through constructors and methods. Application services orchestrate workflows; domain services handle multi-entity logic. Infrastructure layer never contains business logic.

### II. Package by Layered Feature (PbLF)
Code is organized by feature modules (auth, batch, admin) with nested layers (domain, application, infrastructure, presentation). Each feature is self-contained with clear API boundaries. Shared infrastructure (config, security, monitoring) lives in common packages.

### III. Test-Driven Development (NON-NEGOTIABLE)
TDD mandatory for all features: Contract tests → Integration tests → Implementation → Unit tests. Red-Green-Refactor cycle strictly enforced. Tests must be written and approved by user before implementation begins. Minimum 80% code coverage required.

### IV. API-First Design
All functionality exposed via RESTful APIs with OpenAPI 3.0 specifications. Contract tests validate API behavior before implementation. JSON request/response format with standardized error responses. Versioned endpoints (/api/v1/).

### V. Security by Default
JWT authentication required for client APIs (24-hour expiration). Keycloak OAuth2/OIDC for admin endpoints with ROLE_ADMIN. Input validation on all endpoints. Sensitive data (client secrets) hashed with bcrypt. HTTPS required in production.

### VI. Database Optimization
PostgreSQL 16 with table partitioning for high-volume tables (error_logs by month). Indexes on all foreign keys and query columns. Flyway migrations for schema versioning. Connection pooling with HikariCP. Transactions for state changes.

### VII. Observability & Monitoring
Structured logging with SLF4J/Logback (JSON format in production). Metrics exposed via Micrometer/Prometheus. Health checks at /actuator/health. Error logs persisted with metadata (JSONB). Performance target: <1000ms p95 latency.

## Development Standards

### Code Quality Requirements
- Java 21 language features allowed (records, pattern matching, text blocks)
- Spring Boot 3.5.6 coding conventions followed
- No circular dependencies between feature modules
- Repository interfaces define explicit query methods (no magic methods)
- DTOs for API request/response (separate from domain entities)

### Testing Requirements
- Testcontainers for integration tests (PostgreSQL + LocalStack S3)
- Contract tests use MockMvc with JSON assertions
- Unit tests use Mockito for dependencies
- Integration tests verify full request-to-database flows
- Performance tests validate <1000ms p95 latency requirement

### Security Requirements
- No credentials in source code or environment files
- Client secrets hashed before storage
- JWT tokens include site context (siteId, accountId claims)
- Admin endpoints require Keycloak authentication + ROLE_ADMIN
- S3 operations use IAM roles (no hardcoded keys)

## Implementation Workflow

### Task Execution Process
1. Read contract test specification from tasks.md
2. Write failing contract/integration test
3. Get user approval for test coverage
4. Implement minimal code to pass tests (domain → infrastructure → application → presentation)
5. Refactor while keeping tests green
6. Verify 80% coverage threshold met
7. Mark task completed in tasks.md

### Pull Request Requirements
- All tests passing (unit + integration + contract)
- Code coverage ≥80% per module
- Flyway migration included for schema changes
- API contract updated if endpoints changed
- Constitution compliance verified

## Governance

This constitution supersedes all other development practices. Amendments require documentation in this file with version increment and ratification date. All PRs must verify constitutional compliance during review. Complexity must be justified against business requirements. Use CLAUDE.md for runtime development context.

**Version**: 1.0.0 | **Ratified**: 2025-10-06 | **Last Amended**: 2025-10-06
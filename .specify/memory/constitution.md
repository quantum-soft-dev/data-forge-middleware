<!--
Sync Impact Report:
Version Change: Initial → 1.0.0
Modified Principles: N/A (initial creation)
Added Sections:
  - Core Principles (7 principles)
  - Architecture Standards
  - Quality Standards
  - Development Workflow
  - Governance
Removed Sections: N/A
Templates Status:
  ✅ plan-template.md: Constitution Check section aligned
  ✅ spec-template.md: Requirements validation aligned with TDD principle
  ✅ tasks-template.md: Task generation rules aligned with TDD and DDD principles
  ⚠️ commands/*.md: No command files found - manual follow-up needed if commands are added
Follow-up TODOs:
  - Create README.md with project overview
  - Consider creating .claude/commands/ directory for custom slash commands
-->

# Data Forge Middleware Constitution

## Core Principles

### I. Domain-Driven Design (DDD)
The project MUST follow DDD principles with clear separation into four layers:
- **Domain Layer**: Aggregate Roots, Entities, Value Objects, Domain Services, Domain Events, Repository Interfaces
- **Application Layer**: Use Cases, Application Services, DTOs, Command/Query handlers
- **Infrastructure Layer**: Repository Implementations, External Service Clients, Database Configurations, Message Brokers
- **Presentation Layer**: REST Controllers, Request/Response Models, Exception Handlers, API Documentation

**Rationale**: DDD ensures business logic remains pure and testable, decoupled from infrastructure concerns. This enables independent evolution of business rules and technical implementations.

### II. Package by Layered Feature (PbLF)
Code structure MUST be organized by feature with layers nested within each feature module.

Structure pattern:
```
src/main/java/com/bitbi/dfm/
├── [feature]/
│   ├── domain/
│   ├── application/
│   ├── infrastructure/
│   └── presentation/
└── shared/
```

**Rationale**: Feature-based organization improves discoverability, reduces cross-cutting concerns, and enables team ownership of complete vertical slices.

### III. Test-Driven Development (TDD) - NON-NEGOTIABLE
TDD is mandatory with strict red-green-refactor cycle:
1. Write test
2. Run test (MUST fail)
3. Write minimum code to pass test
4. Refactor
5. Repeat

**Backend Coverage**: Minimum 80%
**Frontend Coverage**: Minimum 80%
**Test Naming**: `should{ExpectedBehavior}When{Condition}`

**Rationale**: TDD ensures testable design, prevents regression, and serves as living documentation. The failing-first requirement proves tests are valid.

### IV. API-First Design
REST APIs MUST follow Richardson Maturity Model Level 2:
- Resource-oriented URLs (`/api/v1/resource`)
- Proper HTTP method semantics (GET/POST/PUT/PATCH/DELETE)
- Standard HTTP status codes
- JSON response format without wrappers
- Pagination via offset/limit (`page`, `size`, `sort`)

OpenAPI/Swagger documentation is MANDATORY for all endpoints with:
- Request/response examples
- All parameters described
- All possible status codes documented

**Rationale**: Consistent API design reduces integration friction and enables contract-first development with frontend teams.

### V. Security by Default
Security MUST be integrated from day one:
- Spring Security + Keycloak for authentication/authorization
- JWT tokens for stateless authentication
- Role-based access control (RBAC)
- Secrets stored in AWS Secrets Manager/Vault (production) or application-dev.yml (local only)
- NO passwords, tokens, or API keys in logs
- NO secrets committed to git

**Rationale**: Retrofitting security is costly and error-prone. Security-first design prevents vulnerabilities from entering production.

### VI. Database Query Optimization
Database access MUST be optimized:
- N+1 queries are STRICTLY PROHIBITED
- Use `@EntityGraph`, `JOIN FETCH`, or batch fetching for relationships
- Indexes MANDATORY on foreign keys
- Use `EXPLAIN ANALYZE` to optimize slow queries
- Flyway migrations for schema versioning

**Rationale**: Performance issues from poor database access patterns are difficult to fix later and impact user experience at scale.

### VII. Observability and Documentation
Code MUST be observable and documented:
- **JavaDoc**: MANDATORY for all public methods, classes, interfaces with `@param`, `@return`, `@throws`, and `@example`
- **Logging**: SLF4J + Logback with appropriate levels (DEBUG: dev, INFO: prod)
- **Log Content**: Method parameters (DEBUG), successful operations (INFO), exceptions with stack traces (ERROR)
- **Prohibitions**: NO sensitive data in logs (passwords, tokens, personal data)
- **README**: MANDATORY for all modules with Prerequisites, Setup, Running, Testing, Deployment, API Documentation, Contributing sections

**Rationale**: Documentation and logging are essential for maintenance, onboarding, and debugging production issues.

## Architecture Standards

### Backend (Java/Spring)
- **Language**: Java 21
- **Framework**: Spring Boot 3.5.6
- **Build Tool**: Gradle 9.0 (Kotlin DSL)
- **Database**: PostgreSQL 16 with JPA/Hibernate
- **Connection Pooling**: HikariCP
- **Migrations**: Flyway with `V{version}__{description}.sql` naming
- **Testing**: JUnit 5, Mockito, Test Containers

**Configuration Profiles**:
- `dev`: development
- `test`: testing
- `prod`: production

### Frontend (React/TypeScript)
- **Language**: TypeScript with strict type checking
- **Framework**: React 19
- **Build Tool**: Vite
- **State Management**: TanStack Store (global), TanStack Query (server state)
- **UI Library**: Chakra UI
- **Testing**: Jest + React Testing Library
- **Code Quality**: ESLint + Prettier

**TypeScript Requirements**:
- `noImplicitAny: true`
- `strictNullChecks: true`
- `noUnusedLocals: true`
- `noUnusedParameters: true`

**Project Structure**:
```
src/
├── features/           # Feature modules
├── shared/            # Reusable components, hooks, utils
├── app/               # App-level setup
└── assets/            # Static assets
```

### Naming Conventions
- **Classes/Components**: PascalCase
- **Methods/Variables**: camelCase
- **Constants**: UPPER_SNAKE_CASE
- **Packages**: lowercase
- **Interfaces/Types**: PascalCase WITHOUT prefix (no `I` prefix)
- **Database**: snake_case for tables and columns

## Quality Standards

### Error Handling
**Backend**:
- Global `@RestControllerAdvice` for centralized error handling
- Standard error format with `timestamp`, `status`, `error`, `message`, `path`

**Frontend**:
- Error Boundaries for React component errors
- Toast notifications for user-facing errors (Chakra UI Toast)

### Performance
**Backend**:
- Redis for distributed caching
- `@Async` for asynchronous operations
- Java Virtual Threads for lightweight concurrency
- Cache expensive operations with `@Cacheable`

**Frontend**:
- Code splitting by routes
- Lazy loading with `React.lazy()`
- Image optimization (WebP/AVIF, responsive images, lazy loading)
- TanStack Query with proper `staleTime` and `cacheTime`

### Version Control
**Git Workflow**: GitHub Flow
- `main`: always deployable
- `feature/*`: feature branches
- Pull Requests for merging

**Branch Naming**: `feature/feature-name`, `bugfix/fix-name`, `hotfix/security-patch`

**Commit Messages**: Conventional Commits format
```
<type>(<scope>): <subject>

<body>

<footer>
```
Types: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`

**Branch Protection**:
- PR required for merge to main
- CI checks MUST pass (all tests, build success, 80% coverage)

## Development Workflow

### Test-Driven Development Flow
1. Write test for new functionality
2. Verify test fails (proves test validity)
3. Implement minimum code to pass test
4. Run test suite to verify pass
5. Refactor while keeping tests green
6. Commit after each green cycle

### Code Review Requirements
- Code review is recommended but not mandatory
- All changes MUST pass CI checks
- Constitution compliance MUST be verified
- Complexity deviations MUST be justified in PR description

### Package Management
- **Backend**: Gradle with Spring Boot BOM for dependency management
- **Frontend**: npm (package-lock.json added to .gitignore)

## Governance

### Constitution Authority
This constitution supersedes all other development practices and coding standards. Any deviation MUST be:
1. Documented in PR description or design document
2. Justified with specific technical or business rationale
3. Approved by at least one other team member
4. Tracked in technical debt log if temporary

### Amendment Process
1. Propose amendment via PR to `.specify/memory/constitution.md`
2. Document rationale in PR description
3. Update affected templates in `.specify/templates/`
4. Increment version according to semantic versioning:
   - **MAJOR**: Backward incompatible changes (principle removal/redefinition)
   - **MINOR**: New principle or materially expanded guidance
   - **PATCH**: Clarifications, wording fixes, non-semantic refinements
5. Update `LAST_AMENDED_DATE` to date of merge

### Compliance Verification
All PRs and code reviews MUST verify:
- TDD cycle followed (tests written first, failed, then passed)
- DDD layers respected (no business logic in controllers/infrastructure)
- Test coverage meets 80% threshold
- JavaDoc present for all public APIs
- No secrets in code or logs
- Database queries optimized (no N+1)
- API documentation generated for new endpoints

### Runtime Development Guidance
For agent-specific development guidance, create agent instruction files in the repository root or `.github/` directory:
- `.claude/CLAUDE.md` for Claude Code
- `.github/copilot-instructions.md` for GitHub Copilot
- Other agent-specific files as needed

These files should reference this constitution and provide tool-specific implementation guidance that aligns with constitutional principles.

**Version**: 1.0.0 | **Ratified**: 2025-10-06 | **Last Amended**: 2025-10-06

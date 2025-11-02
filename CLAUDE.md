# data-forge-middleware Development Guidelines

Auto-generated from all feature plans. Last updated: 2025-11-02

## Active Technologies
- Backend: Java 21, Frontend: TypeScript 5.6 with React 19.2 + Backend: Spring Boot 3.5.6, Spring Security 6, Spring Data JPA; Frontend: React 19.2, TanStack Query v5, TanStack Router, shadcn/ui, Tailwind CSS (007-adding-a-site)
- PostgreSQL 16 (sites table already exists, extend with admin_action_logs table via Flyway migration) (007-adding-a-site)

### Backend Stack
- **Java 21** (LTS) with modern language features
- **Spring Boot 3.5.6** - Core framework
- **Spring Security 6** - JWT + OAuth2 Resource Server (Keycloak)
- **Spring Data JPA** - Repository pattern with custom queries
- **PostgreSQL 16** - Primary database with table partitioning
- **Flyway 11** - Database migrations
- **HikariCP** - High-performance connection pooling
- **AWS SDK v2** - S3 file storage
- **Micrometer** - Metrics and observability
- **Logback + Logstash Encoder** - Structured JSON logging
- **SpringDoc OpenAPI 3** - API documentation (Swagger UI)
- **JUnit 5 + Mockito** - Unit testing
- **Testcontainers** - Integration testing (PostgreSQL + LocalStack S3)
- **Keycloak 23.0.1** - Admin Client SDK for user management

### Frontend Stack (Added 2025-10-30 - Spec 005/006)
- **React 19.2** with TypeScript 5.6
- **Vite 5.4** - Build tool and dev server
- **TanStack Query v5** - Server state management
- **React Router v6** - Client-side routing
- **shadcn/ui** - Component library (Radix UI + Tailwind CSS)
- **Tailwind CSS 3.4** - Utility-first styling
- **Axios** - HTTP client with interceptors
- **Zod** - Schema validation
- **React Hook Form** - Form state management
- **Sonner** - Toast notifications
- **Lucide React** - Icon library
- **Vitest + React Testing Library** - Frontend testing
- **OIDC Client TS** - Keycloak authentication client

## Project Structure

```
src/main/java/com/bitbi/dfm/
├── account/              # Account aggregate (multi-tenant root)
├── site/                 # Site aggregate (client authentication)
├── batch/                # Batch aggregate (upload sessions)
├── upload/               # File upload domain
├── error/                # Error logging (partitioned)
├── auth/                 # JWT authentication
└── shared/               # Cross-cutting concerns

src/main/resources/
├── db/migration/         # Flyway SQL migrations
├── application.yml       # Base configuration
├── application-dev.yml   # Development profile
├── application-test.yml  # Testing profile
├── application-prod.yml  # Production profile
└── logback-spring.xml    # Logging configuration

src/test/java/
├── contract/             # Contract tests (MockMvc)
├── integration/          # Integration tests (Testcontainers)
└── [domain]/             # Unit tests per domain
```

## Architecture Decisions

### Domain-Driven Design (DDD)
- **Package by Layered Feature (PbLF)**: Each domain has domain/application/infrastructure/presentation layers
- **Aggregate Roots**: Account, Site, Batch, ErrorLog
- **Value Objects**: JwtToken, FileChecksum, SiteCredentials, BatchStatus
- **Domain Events**: AccountDeactivatedEvent, BatchStartedEvent, BatchCompletedEvent, BatchExpiredEvent
- **Repository Pattern**: Interface in domain, JPA implementation in infrastructure

### Authentication & Authorization
- **Client API**: JWT Bearer tokens (custom implementation with HMAC-SHA256)
- **Admin API**: OAuth2 Resource Server with Keycloak (ROLE_ADMIN required)
- **Basic Auth**: Used only for initial token generation endpoint
- **Token Claims**: siteId, accountId, domain (embedded in JWT)

### Database Design
- **Partitioning**: error_logs table partitioned by month (range partitioning on occurred_at)
- **Soft Delete**: Accounts and sites use isActive flag instead of physical deletion
- **Optimistic Queries**: JOIN FETCH to prevent N+1 queries
- **Indexes**: Strategic indexes on foreign keys and frequently queried columns

### File Storage
- **S3 Integration**: Direct uploads with retry logic (3 attempts)
- **Checksum Validation**: MD5 hash calculated before upload
- **Path Structure**: {accountId}/{domain}/{date}/{time}/{filename}
- **LocalStack**: Used for development/testing environments

### Business Rules Enforcement
1. **One Active Batch Per Site**: Enforced by application service query check
2. **Max 5 Concurrent Batches Per Account**: Counted in-memory at batch start
3. **60-Minute Batch Timeout**: Scheduled task runs every 5 minutes to mark expired
4. **Cascade Deactivation**: Spring event listener pattern for account → sites
5. **500MB File Size Limit**: Validated in controller before service call

## Commands

### Build & Run
```bash
# Build project
./gradlew build

# Run application (development)
./gradlew bootRun --args='--spring.profiles.active=dev'

# Run tests
./gradlew test

# Run integration tests
./gradlew integrationTest

# Generate code coverage
./gradlew jacocoTestReport
```

### Database Migrations
```bash
# Apply pending migrations
./gradlew flywayMigrate

# Check migration status
./gradlew flywayInfo

# Validate migrations
./gradlew flywayValidate

# Rollback (use with caution)
./gradlew flywayUndo
```

### Docker
```bash
# Start LocalStack (S3)
docker-compose up localstack

# Start PostgreSQL
docker-compose up postgres

# Create S3 bucket in LocalStack
aws --endpoint-url=http://localhost:4566 s3 mb s3://dataforge-uploads
```

## Code Style

### Java Conventions
- **Records**: Use for immutable DTOs and value objects
- **Lombok**: `@Getter`, `@NoArgsConstructor` for JPA entities
- **Var keyword**: Avoid - use explicit types for clarity
- **Optionals**: Return from repository methods, avoid in parameters
- **Streams**: Use for collection transformations, avoid excessive chaining

### Naming Conventions
- **Entities**: Singular nouns (Account, Site, Batch)
- **Repositories**: {Entity}Repository interface, Jpa{Entity}Repository implementation
- **Services**: {Domain}Service for application services
- **Controllers**: {Domain}Controller for client API, {Domain}AdminController for admin API
- **DTOs**: Java records for structured responses (BatchResponseDto, ErrorLogResponseDto, etc.)

### Testing Patterns
- **Unit Tests**: Mock all dependencies, focus on business logic
- **Integration Tests**: Use Testcontainers for PostgreSQL, LocalStack for S3
- **Contract Tests**: MockMvc for endpoint verification without full context
- **Test Naming**: `shouldDoSomethingWhenCondition()` format

## Recent Implementation Decisions

### Security Configuration (Updated 2025-10-11)
- **Replaced deprecated keycloak-spring-boot-starter** with spring-boot-starter-oauth2-resource-server
- **Separate security filter chains (FR-005)**: Custom JWT for client API, Keycloak OAuth2 for admin API
  - Client API (`/api/dfc/**`) accepts JWT tokens only (Keycloak returns 403)
  - Admin API (`/api/admin/**`) accepts Keycloak tokens only (JWT returns 403)
  - Filter chain precedence controlled via `@Order` annotation
- **AuthenticationAuditLogger**: Logs auth failures with MDC context (ip, endpoint, method, status, tokenType)
  - Token type detection via JWT structural analysis (algorithm field: HS* = custom JWT, RS* = Keycloak)
- **CSRF disabled**: Stateless API with token-based authentication
- **CORS**: Configured for Actuator endpoints in ActuatorConfiguration

### Error Handling (Updated 2025-10-09)
- **GlobalExceptionHandler**: @RestControllerAdvice for consistent error responses
- **ErrorResponseDto**: Java record with timestamp (Instant), status (Integer), error (String), message (String), path (String)
- **HTTP Status Mapping**:
  - 400 Bad Request - IllegalArgumentException, ambiguous authentication (dual tokens)
  - 403 Forbidden - AccessDeniedException, wrong token type for endpoint
  - 404 Not Found - NoHandlerFoundException
  - 413 Payload Too Large - MaxUploadSizeExceededException
  - 500 Internal Server Error - Generic exceptions

### DTO Records (Added 2025-10-09 - FR-001, FR-002, FR-003)
All endpoints now return structured DTO records instead of Map<String, Object>:

- **BatchResponseDto**: id, batchId, siteId, status, s3Path, uploadedFilesCount, totalSize, hasErrors, startedAt, completedAt
- **ErrorLogResponseDto**: id, batchId, severity, message, source, metadata, occurredAt
- **FileUploadResponseDto**: id, batchId, filename, s3Key, fileSize, checksum, uploadedAt
- **AccountResponseDto**: id, email, name, isActive, createdAt, maxConcurrentBatches
- **SiteResponseDto**: id, accountId, domain, name, isActive, createdAt
- **TokenResponseDto**: token, expiresAt, siteId, domain
- **ErrorResponseDto**: timestamp, status, error, message, path
- **PageResponseDto<T>**: content (List<T>), page, size, totalElements, totalPages

All DTOs include static `fromEntity()` methods for entity-to-DTO mapping.

### Request DTO Validation (Added 2025-10-11 - Spec 004 US1)
Admin API controllers refactored to use typed request DTOs instead of Map<String, Object>:

**Request DTOs created:**
- **CreateAccountRequestDto** (account/presentation/dto/): email (@NotBlank, @Email), name (@NotBlank, @Size(2-100))
- **UpdateAccountRequestDto** (account/presentation/dto/): name (@NotBlank, @Size(2-100))
- **CreateSiteRequestDto** (site/presentation/dto/): domain (@NotBlank, @Size(3-255), @Pattern(^[a-z0-9.-]+$)), displayName (@NotBlank, @Size(2-100))
- **UpdateSiteRequestDto** (site/presentation/dto/): displayName (@NotBlank, @Size(2-100))
- **LogErrorRequestDto** (error/presentation/dto/): type (@NotBlank, @Size(max=100)), message (@NotBlank, @Size(max=1000)), metadata (Map<String, Object>, optional)

**Controllers refactored (all try-catch blocks removed):**
- **AccountAdminController**: createAccount(), updateAccount() - now use @Valid DTOs
- **SiteAdminController**: createSite(), updateSite() - now use @Valid DTOs
- **ErrorLogController**: logStandaloneError(), logError() - now use @Valid DTOs

**Validation handled by:**
- Jakarta Bean Validation 3.0 (@Valid annotation triggers automatic validation)
- GlobalExceptionHandler.handleValidationErrors() - catches MethodArgumentNotValidException, returns ErrorResponseDto (400)
- All validation errors return consistent format: {timestamp, status, error, message, path}

**Benefits:**
- Type safety at compile time (no runtime Map casting errors)
- Automatic validation before controller method execution
- Self-documenting API (OpenAPI schema generation from @Schema annotations)
- Consistent error responses across all endpoints
- Removed 200+ lines of manual validation code

**Status (2025-10-11):**
- ✅ All 5 request DTOs created with validation annotations
- ✅ All 6 endpoints refactored to use DTOs
- ✅ GlobalExceptionHandler updated for MethodArgumentNotValidException
- ✅ Contract tests added for DTO validation (AdminContractTest: 7 account tests, 9 site tests)
- ⚠️ Unit tests need updating (28 compilation errors - tests still use Map instead of DTOs)

### Keycloak-First User Management Architecture (Added 2025-10-30 - Spec 006)

**Key Architectural Decision**: Extend existing `accounts` table instead of creating separate users table.

#### Account-Keycloak Integration Pattern
- **Single Column Extension**: Added `keycloak_user_id VARCHAR(36) UNIQUE` to accounts table (Migration V006)
- **Backwards Compatible**: Nullable column allows existing accounts to work without Keycloak
- **DDD Compliance**: Account remains the aggregate root for user operations
- **No Data Duplication**: Keycloak stores authentication data, PostgreSQL stores business data (phone, company, isActive)

#### Bidirectional Mapping
```java
// PostgreSQL → Keycloak
@Column(name = "keycloak_user_id", length = 36, unique = true)
private String keycloakUserId;  // Immutable Keycloak UUID

// Keycloak → PostgreSQL
user.setAttributes(Map.of("accountId", List.of(account.getId().toString())));
```

#### Keycloak-First Creation Pattern (Two-Phase Commit)
1. **Phase 1**: Create user in Keycloak (authentication layer) with temporary password
2. **Phase 2**: Create Account in PostgreSQL (business layer) with keycloakUserId reference
3. **Bidirectional Link**: Update Keycloak user attributes with PostgreSQL accountId
4. **Compensating Transaction**: If PostgreSQL fails, delete Keycloak user (rollback)
5. **Audit Trail**: Log all operations to admin_action_logs table

**Implementation**: `KeycloakAccountSyncService` handles atomic operations with try-catch rollback
**Critical Error Handling**: Orphaned Keycloak users logged as CRITICAL for manual cleanup

#### Admin Action Audit Logs (Migration V007)
- **Purpose**: Track all administrative actions for compliance and security audits
- **Captured Data**: action type, target account ID, admin account ID, success/failure, IP address, user agent, timestamp
- **Action Types**: CREATE_ACCOUNT, LOCK_ACCOUNT, UNLOCK_ACCOUNT, RESET_PASSWORD, DELETE_ACCOUNT
- **Nullable admin_account_id**: Allows audit logs before Keycloak user mapping is implemented (V013 migration)

#### Keycloak Admin Client Integration
- **Library**: `org.keycloak:keycloak-admin-client:23.0.1`
- **Authentication**: Service account with `CLIENT_CREDENTIALS` grant type
- **Configuration**: Server URL, realm, client ID, client secret via application.yml
- **Wrapper Service**: `KeycloakAdminClient` provides simplified API (createUser, disableUser, enableUser, resetPassword, getUser, getLastLogin)
- **Error Handling**: Maps Keycloak exceptions to domain exceptions (AccountNotFoundException, KeycloakSyncException)

#### User Management Operations
- **Create Account**: POST /api/admin/accounts/with-keycloak (returns temporary password + account details)
- **Lock Account**: POST /api/admin/accounts/{id}/lock (disables Keycloak user, preserves data)
- **Unlock Account**: POST /api/admin/accounts/{id}/unlock (re-enables Keycloak user)
- **Reset Password**: POST /api/admin/accounts/{id}/reset-password (generates new temporary password, expires in 30 days)
- **List Accounts**: GET /api/admin/accounts/with-keycloak?search=email (DB-level filtering with search validation)

#### Search Functionality (PR #11 Optimizations - 2025-10-30)
- **Database-Level Query**: `findAccountsWithKeycloak(@Param("search") String search, Pageable pageable)` with JPQL WHERE and LIKE
- **Performance**: Moved from in-memory filtering to SQL query, fixes N+1 problem
- **Security**: Input validation with `@Size(max=100)` and `@Pattern` to prevent ReDoS attacks
- **Case-Insensitive**: Uses `LOWER()` in SQL for email and name matching
- **Accurate Pagination**: `totalElements` now reflects actual filtered count from database
- **PII Protection**: Logs `"[filtered]"` instead of raw search term
- **ConstraintViolationException Handler**: Added to GlobalExceptionHandler for @RequestParam validation

#### Validation & Security
- **Input Validation**: @Validated on controller + @Size/@Pattern annotations on search parameters
- **Role-Based Access**: All admin endpoints require `ROLE_ADMIN` from Keycloak JWT
- **Prevent Self-Lock**: Admins cannot lock their own accounts (CannotLockOwnAccountException)
- **Duplicate Prevention**: Unique constraint on keycloak_user_id prevents duplicate integrations
- **Test Coverage**: 7 contract tests for search (TC22-TC28) covering validation, filtering, edge cases

#### Edge Cases Handled
- **Keycloak Unavailable**: Fail fast with 503 Service Unavailable
- **Orphaned Keycloak Users**: CRITICAL log + monitoring alert for manual cleanup
- **Legacy Accounts**: Accounts without keycloak_user_id continue working (admin API excludes them from listing)
- **Concurrent Operations**: Spring @Transactional ensures database consistency
- **Session Continuity**: Locking account does NOT terminate existing sessions (graceful degradation)

### Observability
- **Structured Logging**: JSON format in production with Logstash encoder
- **MDC Context**: batchId, siteId, accountId injected into log entries
- **Custom Metrics**: Micrometer counters for batch.started, batch.completed, files.uploaded, error.logged
- **Health Checks**: Database (default) + S3 bucket accessibility (custom)
- **Profile-based Logging**: Human-readable in dev, JSON in prod

### Repository Patterns
- **Custom @Query annotations**: Prevent N+1 queries with JOIN FETCH
- **Partition-aware queries**: error_logs queries include date range for partition pruning
- **Case-insensitive lookups**: LOWER() function for email/domain searches
- **Count queries**: Separate methods for statistics to avoid loading full entities

### Scheduled Tasks
- **BatchTimeoutScheduler**: Cron (0 */5 * * * *) - every 5 minutes
- **PartitionScheduler**: Cron (0 0 0 1 * *) - monthly on 1st at midnight
- **@Scheduled with cron**: Spring's native scheduling, no Quartz dependency
- **Transactional boundaries**: Each scheduled method runs in its own transaction

### Upload History Feature (Added 2025-11-02 - Spec 008)

**Feature**: User-facing upload history with file downloads and Excel export capabilities.

**Key Architectural Decisions**:
- **Cursor-based pagination**: Avoids OFFSET performance issues for large datasets (1000+ uploads)
- **Redis caching**: First page cached for 5 minutes, batch details cached for 30 minutes (COMPLETED batches only)
- **Presigned S3 URLs**: Single file downloads use 15-minute presigned URLs (no server bandwidth consumption)
- **Streaming ZIP**: Multiple files streamed directly from S3 to ZIP archive (no intermediate storage)
- **Memory-efficient Excel**: Apache POI SXSSF with 100-row window (~100MB for 20 files with 10K rows each)

#### User Stories Implemented (P1-P4)
1. **View Upload History List (P1 - MVP)**: Infinite scroll with status indicators (green checkmark/red cross)
2. **View Upload Details (P2)**: Drill-down to file list with metadata (name, size, upload time)
3. **Download Selected Files (P3)**: Single file (presigned URL) or multiple files (ZIP archive)
4. **Excel Export from CSV (P4)**: Generate .xlsx from .csv/.csv.gz files with automatic encoding detection

#### New DTOs (Phase 2)
- **BatchSummaryDto**: Lightweight projection for list view (id, siteId, status, hasErrors, fileCount, totalSize, startedAt, completedAt)
- **BatchDetailDto**: Full batch details with file list
- **FileMetadataDto**: File information for detail view
- **FileDownloadResponseDto**: Presigned URL response with expiry timestamp
- **CursorPageResponseDto<T>**: Generic cursor-based pagination wrapper (items, nextCursor, hasNext)
- **ErrorSummaryDto**: Error information for troubleshooting view

#### Backend Services
- **BatchHistoryService**: Cursor pagination logic, authorization filtering by accountId → siteIds → batches
- **FileDownloadService**: Presigned URL generation (AWS SDK v2), ZIP streaming (Apache Commons Compress)
- **ExcelExportService**: CSV-to-Excel conversion with Apache POI SXSSF, Apache Commons CSV parser
- **EncodingDetectionService**: ICU4J CharsetDetector for UTF-8/Windows-1252/ISO-8859-1 detection
- **CsvDecompressionService**: Gzip decompression for .csv.gz files (streaming API)

#### Frontend Implementation (Feature-Sliced Design)
- **entities/batch/**: API client, TanStack Query hooks (useBatchHistory, useBatchDetails, useBatchErrors)
- **features/upload-history/**: Business logic hooks (useFileDownload, useExcelExport)
- **features/upload-history/ui/**: Presentational components (BatchListView, BatchDetailView, FileTable, DownloadButton, ExcelButton)
- **widgets/upload-history/**: Container components (BatchListWidget, BatchDetailWidget)
- **pages/upload-history/**: Route pages (UploadHistoryPage)

#### Database Optimizations
- **Composite index**: `idx_batches_site_started_id ON batches(site_id, started_at DESC, id DESC)` for cursor pagination performance
- **Projection queries**: `BatchWithFileCountProjection` interface prevents N+1 queries
- **JOIN FETCH**: Eagerly load files with batch details to avoid lazy loading issues

#### Performance Characteristics
- **Batch list first page**: <50ms (cached), <200ms (uncached) for 1000+ uploads
- **Batch details load**: <100ms with JOIN FETCH (prevents N+1 queries)
- **ZIP download**: Streaming (no memory limit), ~5MB/s throughput
- **Excel export**: <30s for 20 CSV files (10K rows each), ~100MB memory footprint

#### Security & Authorization
- **Account-based filtering**: Users only see batches for their sites (JWT accountId → siteIds → batches)
- **Batch ownership verification**: Every operation verifies batch.accountId matches JWT accountId
- **Status-based downloads**: Only COMPLETED batches allow file downloads (403 for IN_PROGRESS)
- **Presigned URL expiry**: 15-minute TTL with automatic regeneration on retry

#### Dependencies Added (build.gradle.kts)
- **Apache POI 5.3.0**: Excel generation (SXSSF streaming API)
- **Apache Commons CSV 1.12.0**: CSV parsing with flexible delimiters
- **Apache Commons Compress 1.28.0**: ZIP/Gzip handling with streaming support
- **ICU4J 76.1**: Advanced encoding detection (90%+ accuracy)

#### Micrometer Metrics
- **batch.history.list**: Timer for batch list query performance
- **batch.details.load**: Timer for batch details query performance
- **s3.presigned.url.generation**: Timer for presigned URL generation
- **downloads.zip.files**: Counter for ZIP downloads (tagged by file count)
- **downloads.zip.duration**: Timer for ZIP streaming performance
- **exports.excel.sheets**: Counter for Excel sheets exported
- **exports.excel.duration**: Timer for Excel generation performance

#### Test Coverage (Testcontainers + MockMvc)
- **Contract tests**: 13 tests covering all API endpoints (TC01-TC13)
- **Integration tests**: 12 tests with Testcontainers PostgreSQL + LocalStack S3
  - BatchHistoryIntegrationTest: 5 tests (cursor pagination, N+1 prevention, error pagination)
  - FileDownloadIntegrationTest: 4 tests (presigned URLs, ZIP streaming)
  - ExcelExportIntegrationTest: 3 tests (UTF-8, Windows-1252, sheet name deduplication)
- **Frontend tests**: 8 tests with Vitest + React Testing Library
  - useBatchHistory hook test
  - useBatchDetails hook test
  - useFileDownload mutation test
  - useExcelExport mutation test
  - Component tests for BatchListView, BatchDetailView, FileTable, DownloadButton

#### Known Limitations
- **Single-region S3**: No multi-region replication for downloads
- **No bandwidth throttling**: Large ZIP/Excel exports can consume bandwidth
- **No resumable downloads**: Failed downloads must restart from beginning
- **Sheet name conflicts**: Excel sheet names truncated to 31 characters (Excel limitation)

#### Future Enhancements
- Batch download progress tracking (WebSocket or polling)
- Multi-part upload support for Excel export resume
- Background job queue for large Excel exports (>50 files)
- Download history tracking (audit log)
- Presigned URL caching with Redis

### OpenAPI Documentation
- **SpringDoc OpenAPI 3**: Automatic API documentation generation
- **Security Schemes**: basicAuth, bearerAuth, oauth2 defined in OpenApiConfiguration
- **Swagger UI**: Accessible at /swagger-ui.html
- **API Spec**: JSON/YAML available at /v3/api-docs

## Known Limitations

1. **Code coverage below 80%**: Overall test coverage is 16% line coverage, 7% branch coverage (as of 2025-10-09). This reflects the pre-existing codebase; Phase 3 additions (DTOs, security separation) are tested but most domain/application layers from foundational implementation remain untested.
2. **Actuator ServletEndpointsSupplier deprecation**: Using deprecated suppliers in ActuatorConfiguration (Spring Boot 3.5.6 compatibility)
3. **No user registration flow**: Accounts/sites created via admin API only
4. **Basic retry logic**: S3 uploads retry 3 times with fixed 1-second delay (no exponential backoff)
5. **In-memory batch counting**: Concurrent batch limit check not atomic across instances
6. **No rate limiting**: API endpoints lack request throttling
7. **Single region**: S3 configuration supports one region only

## Future Enhancements

- [ ] Increase test coverage to 80% (add unit tests for domain/application layers)
- [ ] Multi-region S3 replication
- [ ] Redis cache for token validation
- [ ] Exponential backoff for S3 retries
- [ ] Rate limiting with Redis
- [ ] Distributed batch counting (Redis)
- [ ] WebSocket support for real-time upload progress
- [ ] GraphQL API alternative
- [ ] Multi-part upload for large files (>5GB)

<!-- MANUAL ADDITIONS START -->
<!-- MANUAL ADDITIONS END -->

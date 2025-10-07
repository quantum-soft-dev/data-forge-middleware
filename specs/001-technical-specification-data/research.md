# Phase 0: Research & Technology Decisions

**Date**: 2025-10-06
**Status**: Complete

## Technology Stack Research

### 1. Core Framework: Spring Boot 3.5.6 with Java 21

**Decision**: Use Spring Boot 3.5.6 with Java 21
**Rationale**:
- Spring Boot 3.x requires Java 17+ and provides native support for Java 21 features (Virtual Threads, Pattern Matching, Records)
- Mature ecosystem with extensive Spring Data JPA, Spring Security, Spring Cloud integrations
- Built-in Actuator for health checks and metrics (NFR-062 to NFR-064)
- Excellent Testcontainers integration for integration testing with real PostgreSQL and S3 (LocalStack)
- Virtual Threads (Project Loom) provide lightweight concurrency for I/O-bound operations (file uploads, S3 operations)

**Alternatives Considered**:
- **Micronaut 4.x**: Faster startup, lower memory footprint, but less mature ecosystem for Keycloak and enterprise patterns
- **Quarkus 3.x**: Native compilation benefits not needed for this workload; Spring Boot ecosystem more aligned with DDD patterns

**Best Practices**:
- Use `@SpringBootApplication` with component scanning limited to `com.bitbi.dfm`
- Externalize configuration via `application.yml` with profiles (dev, test, prod)
- Enable virtual threads via `spring.threads.virtual.enabled=true` for Tomcat executor
- Use `@ConfigurationProperties` for typed configuration binding (batch timeouts, file size limits)

---

### 2. Database: PostgreSQL 16 with Table Partitioning

**Decision**: PostgreSQL 16 with native range partitioning for `error_logs` table
**Rationale**:
- Native support for range partitioning by `occurred_at` timestamp (FR-044)
- Excellent JPA/Hibernate integration via Spring Data JPA
- JSONB type for flexible `error_logs.metadata` storage with GIN indexing (FR-042)
- Robust transaction support for batch lifecycle state transitions
- Performant B-tree indexes on foreign keys (account_id, site_id, batch_id)

**Alternatives Considered**:
- **MySQL 8.x**: Weaker partition pruning performance; no native JSONB equivalent
- **MongoDB**: NoSQL not justified for strongly relational data model (Account → Site → Batch → UploadedFile)

**Best Practices**:
- Partition `error_logs` by month: `FOR VALUES FROM ('2025-10-01') TO ('2025-11-01')`
- Create partitions proactively via scheduled task (FR-075) one month in advance
- Use `@EntityGraph` to prevent N+1 queries when fetching Site with Account
- Flyway migration naming: `V{version}__{description}.sql` (e.g., `V1__create_accounts_table.sql`)
- Foreign key indexes: `CREATE INDEX idx_sites_account_id ON sites(account_id)`

---

### 3. File Storage: AWS S3 with AWS SDK for Java 2.x

**Decision**: AWS SDK for Java 2.x (S3 client) with custom retry logic
**Rationale**:
- Hierarchical key structure supports S3 "directories": `{accountId}/{domain}/{YYYY-MM-DD}/{HH-MM}/` (FR-025, FR-070)
- S3-compatible interfaces allow LocalStack for local development and testing
- Built-in checksumming (MD5, SHA-256) via `PutObjectRequest.checksumAlgorithm()` (FR-033)
- Presigned URLs can be added later for direct client uploads (future phase)

**Alternatives Considered**:
- **MinIO**: S3-compatible but requires separate infrastructure deployment; AWS S3 is managed service
- **Azure Blob Storage**: Not S3-compatible; would require different client SDK

**Best Practices**:
- Implement custom `S3RetryPolicy` with fixed interval (NFR-006): 3 attempts, configurable delay (e.g., 1 second)
- Use `S3TransferManager` for large file uploads (>5MB) with multipart upload support
- Configure timeout: `S3ClientBuilder.overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(30)).build())`
- LocalStack for integration tests: `@Testcontainers` with `localstack/localstack:latest` image
- S3 bucket policy: private ACL, enforce HTTPS, enable versioning for compliance (future phase)

---

### 4. Authentication: JWT + Spring Security + Keycloak

**Decision**: Custom JWT provider for client auth + Keycloak for admin auth
**Rationale**:
- **Client Authentication**: Custom JWT issued after Basic Auth (domain:clientSecret) allows stateless, scalable authentication (FR-001, FR-002)
- **Admin Authentication**: Keycloak provides enterprise-grade RBAC, OIDC/SAML support, user management UI (FR-005)
- Spring Security integrates seamlessly with both JWT validation and Keycloak Spring Boot Adapter
- JWT payload includes `siteId` and `accountId` for authorization checks without database lookups

**Alternatives Considered**:
- **Spring Security OAuth2 Resource Server**: Requires OAuth2 provider for client apps; overkill for simple domain:secret credentials
- **Auth0 / Okta**: SaaS overhead and cost not justified for single-tenant deployment

**Best Practices**:
- JWT library: `io.jsonwebtoken:jjwt-api:0.12.x` with HMAC-SHA256 signing
- Token expiration: 24 hours (86400 seconds) with support for renewal before expiry (FR-004)
- JWT claims: `sub` (siteId), `accountId`, `domain`, `iat`, `exp`
- Keycloak realm: `dfm`, client: `dfm-backend`, role: `ADMIN`
- Admin endpoints secured via `@PreAuthorize("hasRole('ADMIN')")` or SecurityFilterChain config
- No password storage: `clientSecret` stored as plain UUID in `sites` table (validated via constant-time comparison)

---

### 5. Database Migrations: Flyway

**Decision**: Flyway for versioned SQL migrations
**Rationale**:
- SQL-first approach aligns with DBA review requirements for production schema changes
- Version control integration: migrations are code-reviewed alongside application code
- Automatic execution on application startup via Spring Boot Flyway auto-configuration
- Supports PostgreSQL-specific features (partitioning, JSONB, indexes)

**Alternatives Considered**:
- **Liquibase**: XML/YAML-based; SQL migrations preferred for transparency
- **JPA `ddl-auto=update`**: Unsafe for production; no migration history or rollback capability

**Best Practices**:
- Naming: `V{version}__{description}.sql` (e.g., `V1__create_accounts_table.sql`)
- Baseline: `flyway.baseline-on-migrate=true` for existing databases
- Location: `src/main/resources/db/migration`
- Never modify committed migrations; create new `V{n+1}__` migration to alter schema
- Test migrations with Testcontainers PostgreSQL in CI pipeline

---

### 6. Testing: JUnit 5 + Mockito + Testcontainers

**Decision**: JUnit 5 with Mockito for unit tests, Testcontainers for integration tests
**Rationale**:
- JUnit 5 Jupiter API: modern assertions, parameterized tests, test lifecycle annotations
- Mockito 5.x: fluent mocking API for service layer tests, compatible with Java 21
- Testcontainers: real PostgreSQL 16 and LocalStack S3 for integration tests (prevents test-specific database mocking)
- Contract tests: Spring MockMvc + OpenAPI validation (springdoc-openapi-starter-webmvc-ui)

**Best Practices**:
- Test naming: `shouldExpectedBehaviorWhenCondition()` (e.g., `shouldRejectDuplicateFileNameWhenUploadingToSameBatch()`)
- Unit tests: `@ExtendWith(MockitoExtension.class)` with `@Mock` and `@InjectMocks`
- Integration tests: `@SpringBootTest` with `@Testcontainers` and `@Container`
- Coverage: JaCoCo plugin with 80% minimum threshold enforced in CI
- Contract tests: Validate OpenAPI spec matches actual controller signatures

---

### 7. Build Tool: Gradle 9.0 with Kotlin DSL

**Decision**: Gradle 9.0 with Kotlin DSL (`build.gradle.kts`)
**Rationale**:
- Kotlin DSL provides type-safe configuration with IDE autocomplete
- Gradle 9.0 supports Java 21 and configuration caching for faster builds
- Better dependency management than Maven for Spring Boot BOM and custom tasks
- Native support for multi-module projects (future frontend module possible)

**Best Practices**:
- Use Spring Boot Gradle Plugin for dependency management: `id("org.springframework.boot") version "3.5.6"`
- JaCoCo plugin for coverage: `id("jacoco")`
- Kotlin DSL: `plugins { id("org.springframework.boot") version "3.5.6" }`
- Dependency versions centralized via `libs.versions.toml` or `buildSrc`

---

### 8. Logging: SLF4J + Logback with JSON formatting

**Decision**: SLF4J API with Logback backend, JSON formatting in production
**Rationale**:
- SLF4J is de facto standard for Java logging, Spring Boot includes it by default
- Logback supports structured JSON logging via `logstash-logback-encoder` for centralized log aggregation (ELK/CloudWatch)
- Configuration per profile: DEBUG for dev, INFO for prod (constitution requirement)

**Best Practices**:
- Sensitive data filtering: No passwords, tokens, PII in logs (constitution principle V)
- Contextual logging: MDC (Mapped Diagnostic Context) for `batchId`, `siteId` in thread-local context
- Log levels:
  - **DEBUG**: Method parameters, SQL queries (dev only)
  - **INFO**: Batch lifecycle events (started, completed), file uploads, scheduled tasks
  - **ERROR**: Exceptions with stack traces, S3 retry failures
- JSON format (production): `{"timestamp":"2025-10-06T10:30:00Z","level":"INFO","logger":"com.bitbi.dfm.batch.application.BatchService","message":"Batch created","batchId":"a1b2c3d4-...","siteId":"b2c3d4e5-..."}`

---

### 9. Metrics & Monitoring: Micrometer + Spring Boot Actuator

**Decision**: Micrometer for metrics, Spring Boot Actuator for health checks
**Rationale**:
- Micrometer provides vendor-neutral metrics API compatible with Prometheus, Datadog, CloudWatch
- Actuator endpoints: `/actuator/health`, `/actuator/health/db`, `/actuator/health/s3`, `/actuator/metrics` (FR-062 to FR-064)
- Custom metrics for batch operations, file uploads, errors (FR-065 to FR-069)

**Best Practices**:
- Custom counters: `MeterRegistry.counter("batch.started", "accountId", "siteId").increment()`
- Gauges for active batches: `MeterRegistry.gauge("batch.active.count", accountIdTag, atomicIntegerSupplier)`
- Histograms for upload duration: `Timer.builder("upload.duration").register(registry).record(duration)`
- Health indicators: `@Component implements HealthIndicator` for S3 connectivity check

---

### 10. Configuration Management: Environment Variables + Spring Profiles

**Decision**: YAML-based configuration with Spring profiles (dev, test, prod)
**Rationale**:
- `application.yml` for defaults, `application-dev.yml` and `application-prod.yml` for overrides
- Environment variables for secrets: `${DB_PASSWORD}`, `${S3_ACCESS_KEY}`, `${JWT_SECRET}`
- AWS Secrets Manager integration via `spring-cloud-aws-secrets-manager-config` (production)

**Best Practices**:
- Local dev: `application-dev.yml` with localhost PostgreSQL, LocalStack S3, in-memory Keycloak
- Production: Environment variables override YAML values, secrets from AWS Secrets Manager
- Validation: `@ConfigurationProperties(prefix = "batch")` with `@Validated` and JSR-303 annotations
- Example configuration structure:
```yaml
batch:
  timeout:
    minutes: 60
  max:
    active:
      per-account: 10
upload:
  max:
    file-size-mb: 128
    files-per-batch: 1000
s3:
  endpoint: ${S3_ENDPOINT:https://s3.amazonaws.com}
  bucket-name: ${S3_BUCKET_NAME:data-forge-bucket}
```

---

## Resolved Unknowns

All technical context items were pre-specified in the feature specification. No NEEDS CLARIFICATION markers required research.

**Status**: ✅ All technology decisions finalized, ready for Phase 1 design.

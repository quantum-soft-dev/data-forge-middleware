# data-forge-middleware Development Guidelines

Auto-generated from all feature plans. Last updated: 2025-10-06

## Active Technologies
- **Java 21** (LTS) with modern language features
- **Spring Boot 3.5.6** - Core framework
- **Spring Security** - JWT + OAuth2 Resource Server (Keycloak)
- **Spring Data JPA** - Repository pattern with custom queries
- **PostgreSQL 16** - Primary database with table partitioning
- **Flyway 11** - Database migrations
- **HikariCP** - High-performance connection pooling
- **AWS SDK v2** - S3 file storage
- **Micrometer** - Metrics and observability
- **Logback + Logstash Encoder** - Structured JSON logging
- **SpringDoc OpenAPI** - API documentation (Swagger UI)
- **JUnit 5 + Mockito** - Unit testing
- **Testcontainers** - Integration testing
- Java 21 (LTS) + Spring Boot 3.5.6, Spring Security (JWT + OAuth2 Resource Server), Lombok, Jackson (002-additions-to-backend)
- PostgreSQL 16 (via Spring Data JPA) (002-additions-to-backend)

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
- **DTOs**: Inline Map<String, Object> for responses (avoiding DTO explosion)

### Testing Patterns
- **Unit Tests**: Mock all dependencies, focus on business logic
- **Integration Tests**: Use Testcontainers for PostgreSQL, LocalStack for S3
- **Contract Tests**: MockMvc for endpoint verification without full context
- **Test Naming**: `shouldDoSomethingWhenCondition()` format

## Recent Implementation Decisions

### Security Configuration
- **Replaced deprecated keycloak-spring-boot-starter** with spring-boot-starter-oauth2-resource-server
- **Dual authentication**: Custom JWT for client API, Keycloak OAuth2 for admin API
- **CSRF disabled**: Stateless API with token-based authentication
- **CORS**: Configured for Actuator endpoints in ActuatorConfiguration

### Error Handling
- **GlobalExceptionHandler**: @RestControllerAdvice for consistent error responses
- **ErrorResponse DTO**: Record-based with timestamp, status, error, message, path
- **HTTP Status Mapping**:
  - 400 Bad Request - IllegalArgumentException
  - 403 Forbidden - AccessDeniedException
  - 404 Not Found - NoHandlerFoundException
  - 413 Payload Too Large - MaxUploadSizeExceededException
  - 500 Internal Server Error - Generic exceptions

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

### OpenAPI Documentation
- **SpringDoc OpenAPI 3**: Automatic API documentation generation
- **Security Schemes**: basicAuth, bearerAuth, oauth2 defined in OpenApiConfiguration
- **Swagger UI**: Accessible at /swagger-ui.html
- **API Spec**: JSON/YAML available at /v3/api-docs

## Known Limitations

1. **Actuator ServletEndpointsSupplier deprecation**: Using deprecated suppliers in ActuatorConfiguration (Spring Boot 3.5.6 compatibility)
2. **No user registration flow**: Accounts/sites created via admin API only
3. **Basic retry logic**: S3 uploads retry 3 times with fixed 1-second delay (no exponential backoff)
4. **In-memory batch counting**: Concurrent batch limit check not atomic across instances
5. **No rate limiting**: API endpoints lack request throttling
6. **Single region**: S3 configuration supports one region only

## Future Enhancements

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

# Data Forge Middleware

A Spring Boot 3.5.6 middleware service for secure batch file uploads with AWS S3 integration, designed for multi-tenant environments.

## Overview

Data Forge Middleware provides a RESTful API for managing batch file uploads from client sites to AWS S3 storage. It features:

- **Multi-tenant Architecture**: Account-based isolation with site-level authentication
- **Batch Upload Management**: Track upload sessions with lifecycle states and metadata
- **S3 Integration**: Secure file storage with automatic retry and checksum validation
- **Admin Portal**: Auth0-secured (OAuth2 / JWT) endpoints for account and site management
- **Observability**: Structured JSON logging, metrics, and health checks
- **PostgreSQL 16**: Partitioned error logs and optimized queries

## Prerequisites

- **Java 25** (Temurin/Corretto recommended)
- **PostgreSQL 16+** with partitioning support
- **AWS S3** or LocalStack for development
- **Auth0 tenant** (for the Admin/UI API and the frontend; see [docs/local-auth0.md](docs/local-auth0.md))
- **Gradle 9.0+** (wrapper included)

## Quick Start

### Option 0: DevContainer (Local Dev Environment)

This repo includes a DevContainer that starts infrastructure (PostgreSQL, Redis, LocalStack S3)
via `docker-compose.dev.yml` and provides a Java 25 + Node 20 dev environment.

1. Configure backend env vars in `.env` (see `.env.example`).
2. Configure frontend env vars in `frontend/.env.local` (see `frontend/.env.local.example`).
3. Open the folder `data-forge-middleware` in VS Code (not the parent `bit-bi` folder).
4. In VS Code: “Dev Containers: Reopen in Container”.
5. Start backend: `./gradlew bootRun`
6. Start frontend: `cd frontend && npm install && npm run dev`

Notes:
- Auth is via Auth0. Keycloak has been fully removed (see [Legacy: Keycloak](#legacy-keycloak)).
- LocalStack bucket `dfm-uploads` is created automatically by `docker-compose.dev.yml`.
- Auth0 setup details: `docs/local-auth0.md`
- If backend fails with Flyway checksum mismatch, wipe local volumes: `docker-compose -f docker-compose.dev.yml down -v` then `up -d`.

### Option 1: Development with IntelliJ IDEA (Recommended)

Run infrastructure services in Docker and DFM from IDE for debugging:

```bash
# Start infrastructure (PostgreSQL, Redis, LocalStack)
./scripts/docker-dev.sh start

# Or manually
docker-compose -f docker-compose.dev.yml up -d
```

Then in IntelliJ IDEA:
1. Open Run Configuration
2. Set **Active profiles**: `dev`
3. Run or Debug the application

Infrastructure services:
- **PostgreSQL**: localhost:5432 (user: `postgres`, password: `postgres`, database: `dfm`)
- **Redis**: localhost:6379
- **LocalStack S3**: http://localhost:4566 (bucket: dfm-uploads)

Authentication is not part of the local stack — the backend validates Auth0-issued
JWTs against your tenant (`AUTH0_*` env vars, see [docs/local-auth0.md](docs/local-auth0.md)).

**Stop infrastructure:**
```bash
./scripts/docker-dev.sh stop
```

See [docker-compose.dev.yml](docker-compose.dev.yml) for configuration details.

### Option 2: Full Docker Stack

The easiest way to run the complete stack:

```bash
# Start all services (PostgreSQL, Redis, LocalStack S3, DFM Backend)
docker-compose up -d

# Check services are healthy
docker-compose ps

# View logs
docker-compose logs -f dfm-backend
```

Services will be available at:
- **Application API**: http://localhost:8080
- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **PostgreSQL**: localhost:5432 (database: `dfm`)
- **LocalStack S3**: http://localhost:4566

The backend requires `AUTH0_*` environment variables (see `.env.example`); roles come
from the `https://api.dataforge.com/roles` custom claim on the Auth0 access token —
there are no locally provisioned identity-provider users.

See [docker/README.md](docker/README.md) for detailed Docker configuration.

### Option 2: Manual Setup

### 1. Clone and Build

```bash
git clone <repository-url>
cd data-forge-middleware
./gradlew build
```

### 2. Configure Database

Create PostgreSQL database:

```sql
CREATE DATABASE dataforge;
CREATE USER dataforge_user WITH PASSWORD 'your-password';
GRANT ALL PRIVILEGES ON DATABASE dataforge TO dataforge_user;
```

### 3. Configure Application

Copy example configuration:

```bash
cp src/main/resources/application-dev.yml.example src/main/resources/application-dev.yml
```

Edit `application-dev.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/dataforge
    username: dataforge_user
    password: your-password

s3:
  bucket:
    name: dataforge-uploads
  endpoint: http://localhost:4566  # LocalStack
  region: us-east-1
  access-key: test
  secret-key: test

jwt:
  secret: your-256-bit-secret-key-here-minimum-32-chars
  expiration-minutes: 60

batch:
  timeout-minutes: 60
```

### 4. Start LocalStack (Development)

```bash
docker run -d -p 4566:4566 -p 4571:4571 \
  --name localstack \
  -e SERVICES=s3 \
  -e DEBUG=1 \
  localstack/localstack:latest

# Create S3 bucket
aws --endpoint-url=http://localhost:4566 s3 mb s3://dataforge-uploads
```

### 5. Run Flyway Migrations

```bash
./gradlew flywayMigrate
```

### 6. Start Application

```bash
./gradlew bootRun --args='--spring.profiles.active=dev'
```

Application starts on `http://localhost:8080`

## API Documentation

### Swagger UI

Access interactive API documentation:

```
http://localhost:8080/swagger-ui.html
```

### Client Authentication Flow (Device Authorization Flow)

This is the only way a client obtains a token. There is no secret-based token endpoint —
the legacy `POST /api/v1/auth/token` was removed along with the rest of the v1 client surface.

#### 1. Device requests authorization

```bash
curl -X POST http://localhost:8080/api/v1/device/authorize \
  -H "Content-Type: application/json" \
  -d '{
    "siteName": "warehouse-01",
    "siteDescription": "Warehouse 01",
    "siteType": "DBF"
  }'
```

Response:
```json
{
  "deviceCode": "GmRhmhcxhwAzkoEqiMEg_DnyEysNkuNhszIySk9eS",
  "userCode": "WDJB-MJHT",
  "verificationUri": "https://app.dataforge.com/device-verify",
  "verificationUriComplete": "https://app.dataforge.com/device-verify?code=WDJB-MJHT",
  "expiresIn": 900,
  "interval": 5
}
```

#### 2. User approves in the web UI

The account owner opens `verificationUriComplete`, authenticates via Auth0, and approves the
pending device. Auth0 authenticates the **approver**, not the device.

#### 3. Device polls for tokens

Poll no faster than `interval` seconds. Before approval the endpoint answers
`authorization_pending`.

```bash
curl -X POST http://localhost:8080/api/v1/device/token \
  -H "Content-Type: application/json" \
  -d '{"deviceCode": "GmRhmhcxhwAzkoEqiMEg_DnyEysNkuNhszIySk9eS"}'
```

Response:
```json
{
  "siteId": "550e8400-e29b-41d4-a716-446655440000",
  "siteName": "warehouse-01",
  "accessToken": "eyJhbGciOiJIUzI1NiIs...",
  "refreshToken": "kA9v...",
  "accessTokenExpiresAt": "2026-01-01T13:00:00Z",
  "refreshTokenExpiresAt": "2026-04-01T12:00:00Z",
  "apiBaseUrl": "https://api.dataforge.com"
}
```

#### 4. Send data over gRPC

Data does not go over REST. The client streams changes to the Delta v2 gRPC service
(default port `9090`, contract in `src/main/proto/delta-ingestion.proto`), passing the access
token as a Bearer credential in gRPC metadata. See
[docs/delta-client-v2-guide.md](docs/delta-client-v2-guide.md).

#### 5. Refresh the access token

Refresh tokens rotate on every use: the presented token is revoked and a new one is returned.
Presenting an already-rotated token is treated as a leak and revokes that rotation family.

```bash
curl -X POST http://localhost:8080/api/v1/device/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refreshToken": "kA9v..."}'
```

### Admin API (Auth0 Access Token Required)

#### Create Account

```bash
curl -X POST http://localhost:8080/api/v1/accounts \
  -H "Authorization: Bearer <auth0-access-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "name": "Example User"
  }'
```

#### Create Site

```bash
curl -X POST http://localhost:8080/api/v1/accounts/{accountId}/sites \
  -H "Authorization: Bearer <auth0-access-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "siteName": "warehouse-01",
    "displayName": "Warehouse 01"
  }'
```

### Legacy: Keycloak

Keycloak was the original identity provider and has been fully replaced by Auth0.
The OAuth2 resource server is configured against the Auth0 issuer
(`auth/config/Auth0SecurityConfig.java`, `shared/config/SecurityConfiguration.java`,
`spring.security.oauth2.resourceserver.jwt.issuer-uri` = `https://${auth0.domain}/`),
and the frontend uses `@auth0/auth0-react`.

The Keycloak-era artefacts have been removed: the `keycloak` service and issuer in
`docker-compose.prod.yml`, the `docker/keycloak/` realm files, the `keycloak` database and
role in `docker/postgres/init.sql`, and the dead `src/main/resources/application-test.yml`
(shadowed by `src/test/resources/application-test.yml` on the test classpath).

One deliberate exception remains in code: `Auth0RoleConverter` still falls back to
Keycloak's `realm_access.roles` claim, because the test harness itself
(`config/TestSecurityConfig`) mints tokens in that shape. Removing the fallback means
migrating the harness to Auth0-shaped claims first, and is tracked separately.

## Architecture

### Domain-Driven Design

```
src/main/java/com/bitbi/dfm/
├── account/
│   ├── domain/              # Account aggregate
│   ├── application/         # AccountService, statistics
│   ├── infrastructure/      # JpaAccountRepository
│   └── presentation/        # AccountAdminController
├── site/
│   ├── domain/              # Site aggregate
│   ├── application/         # SiteService, event handlers
│   ├── infrastructure/      # JpaSiteRepository
│   └── presentation/        # SiteAdminController
├── batch/
│   ├── domain/              # Batch aggregate, BatchStatus
│   ├── application/         # BatchLifecycleService, timeout scheduler
│   ├── infrastructure/      # JpaBatchRepository
│   └── presentation/        # BatchController
├── upload/
│   ├── domain/              # UploadedFile, FileChecksum
│   ├── application/         # FileUploadService
│   ├── infrastructure/      # S3FileStorageService, config
│   └── presentation/        # FileUploadController
├── error/
│   ├── domain/              # ErrorLog (partitioned)
│   ├── application/         # ErrorLoggingService, export
│   ├── infrastructure/      # JpaErrorLogRepository, partition scheduler
│   └── presentation/        # ErrorLogController
├── auth/
│   ├── domain/              # JwtToken value object
│   ├── application/         # TokenService
│   ├── infrastructure/      # JwtTokenProvider, security config
│   └── presentation/        # AuthController
└── shared/
    ├── config/              # OpenAPI, Actuator, Metrics
    ├── exception/           # GlobalExceptionHandler, ErrorResponse
    └── health/              # S3HealthIndicator
```

### Database Schema

- **accounts**: User accounts with soft delete
- **sites**: Client sites with domain-based authentication
- **batches**: Upload sessions with lifecycle tracking
- **uploaded_files**: File metadata with S3 keys and checksums
- **error_logs**: Partitioned by month with JSONB metadata

### Key Business Rules

1. **One Active Batch Per Site**: Only one IN_PROGRESS batch allowed per site
2. **Concurrent Batch Limit**: Maximum 5 active batches per account
3. **Batch Timeout**: Batches auto-expire after 60 minutes (configurable)
4. **Cascade Deactivation**: Deactivating account deactivates all sites

## Testing

### Run All Tests

```bash
./gradlew test
```

### Run Integration Tests

```bash
./gradlew integrationTest
```

### Run Contract Tests

```bash
./gradlew contractTest
```

### Code Coverage

```bash
./gradlew jacocoTestReport
open build/reports/jacoco/test/html/index.html
```

### The test JVM

Every Gradle `Test` task runs with `maxHeapSize = "2g"` (`build.gradle.kts`, `tasks.withType<Test>`).
Gradle's default is 512 MB, and the whole suite runs in **one** JVM — ~2470 tests, 444 classes and
24 cached Spring contexts held for the length of the run — so that default left no margin and CI
failed intermittently, naming a different, innocent test each time.

The value is measured rather than guessed. A `-Xlog:gc` pass over the full suite at a deliberately
generous 3 GiB never let G1 expand past 1014 MB, with the highest occupancy after a collection at
801 MB and the highest before one at 965 MB — so 1 GiB sits on the cliff and 512 MB was under it.
Re-measure before moving the value:

```bash
./gradlew test -PtestHeapLog     # one GC log per Test task under build/reports/test-heap/
```

One JVM is the intended shape. `forkEvery` would bound the accumulation by throwing away the
Spring `TestContext` cache — which is what makes 444 classes affordable — and it is the right tool
only for accumulation that has no ceiling; this accumulation is one context per distinct
configuration, a property of the test classes rather than of the test count. `maxParallelForks`
stays at 1 because the suite deliberately shares one PostgreSQL database across every context.

If the ceiling is reached anyway, `-XX:+ExitOnOutOfMemoryError` ends the JVM at the allocation that
failed instead of letting the error be caught and re-reported as some unrelated test's
`BeanCreationException`, and `-XX:+HeapDumpOnOutOfMemoryError` leaves the evidence in
`build/reports/test-oom/`. The build then fails as:

```
java.lang.OutOfMemoryError: Java heap space
Dumping heap to .../build/reports/test-oom/java_pid138960.hprof ...
Terminating due to java.lang.OutOfMemoryError: Java heap space
> Process 'Gradle Test Executor 4' finished with non-zero exit value 3
```

No test is named, because no test was at fault. The dump is deliberately **not** uploaded as a CI
artifact — at this ceiling it can reach two gigabytes; reproduce locally instead.

## Monitoring

### Health Check

```bash
curl http://localhost:8080/actuator/health
```

Response:
```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "s3": { "status": "UP", "details": { "bucket": "dataforge-uploads" } },
    "diskSpace": { "status": "UP" }
  }
}
```

### Metrics

```bash
curl http://localhost:8080/actuator/metrics
```

Custom metrics:
- `batch.started` - Total batches started
- `batch.completed` - Total batches completed
- `batch.failed` - Total batches failed
- `files.uploaded` - Total files uploaded
- `error.logged` - Total errors logged

### Logs

Structured JSON logging in production:

```json
{
  "@timestamp": "2024-01-01T12:00:00.000Z",
  "level": "INFO",
  "logger": "com.bitbi.dfm.batch.application.BatchLifecycleService",
  "message": "Starting new batch",
  "batchId": "550e8400-e29b-41d4-a716-446655440000",
  "siteId": "123e4567-e89b-12d3-a456-426614174000",
  "application": "data-forge-middleware"
}
```

## Deployment

### Production Configuration

```yaml
spring:
  profiles:
    active: prod
  datasource:
    url: jdbc:postgresql://<rds-endpoint>:5432/dataforge
    hikari:
      # Do not raise this without redoing the derivation beside the key in application.yml
      # (issue #161). The pool is per replica and the database's max_connections is not, so the
      # bound is (maxReplicas + maxSurge) x pool + an operator reserve <= max_connections, and at
      # the replica ceiling this deployment declares a server left at PostgreSQL's default 100
      # allows at most 12. Raising it needs `SHOW max_connections` on the actual server first —
      # BackgroundConnectionDemandTest fails until that number is recorded there.
      maximum-pool-size: 10
      minimum-idle: 5

s3:
  bucket:
    name: prod-dataforge-uploads
  region: us-east-1
  # Uses IAM role credentials in production

logging:
  level:
    root: INFO
    com.bitbi.dfm: INFO
```

### Docker Deployment

```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Build and run:

```bash
./gradlew bootJar
docker build -t dataforge-middleware .
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/dataforge \
  dataforge-middleware
```

## Contributing

### Code Style

- Follow Java 25 conventions
- Use Lombok for boilerplate reduction
- Domain-driven design principles
- Package by layered feature (PbLF)

### Pull Request Process

1. Create feature branch: `git checkout -b feature/my-feature`
2. Write tests for new functionality
3. Ensure all tests pass: `./gradlew test`
4. Update documentation as needed
5. Submit PR with clear description

## License

Proprietary - Bit BI

## Support

For issues or questions:
- Email: support@bitbi.com
- Documentation: https://docs.dataforge.bitbi.com

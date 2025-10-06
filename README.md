# Data Forge Middleware

A Spring Boot 3.5.6 middleware service for secure batch file uploads with AWS S3 integration, designed for multi-tenant environments.

## Overview

Data Forge Middleware provides a RESTful API for managing batch file uploads from client sites to AWS S3 storage. It features:

- **Multi-tenant Architecture**: Account-based isolation with site-level authentication
- **Batch Upload Management**: Track upload sessions with lifecycle states and metadata
- **S3 Integration**: Secure file storage with automatic retry and checksum validation
- **Admin Portal**: Keycloak-secured endpoints for account and site management
- **Observability**: Structured JSON logging, metrics, and health checks
- **PostgreSQL 16**: Partitioned error logs and optimized queries

## Prerequisites

- **Java 21** (Temurin/Corretto recommended)
- **PostgreSQL 16+** with partitioning support
- **AWS S3** or LocalStack for development
- **Keycloak** (optional, for admin endpoints)
- **Gradle 9.0+** (wrapper included)

## Quick Start

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

### Authentication Flow

#### 1. Generate JWT Token (Client API)

```bash
curl -X POST http://localhost:8080/api/v1/auth/token \
  -H "Authorization: Basic $(echo -n 'example.com:client-secret' | base64)" \
  -H "Content-Type: application/json"
```

Response:
```json
{
  "token": "eyJhbGciOiJIUzI1NiIs...",
  "expiresAt": "2024-01-01T13:00:00",
  "tokenType": "Bearer"
}
```

#### 2. Start Batch Upload

```bash
curl -X POST http://localhost:8080/api/v1/batch/start \
  -H "Authorization: Bearer <jwt-token>" \
  -H "Content-Type: application/json"
```

Response:
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "siteId": "123e4567-e89b-12d3-a456-426614174000",
  "status": "IN_PROGRESS",
  "s3Path": "account-id/example.com/2024-01-01/12-00/",
  "uploadedFilesCount": 0,
  "totalSize": 0,
  "hasErrors": false,
  "startedAt": "2024-01-01T12:00:00"
}
```

#### 3. Upload Files

```bash
curl -X POST http://localhost:8080/api/v1/batch/{batchId}/upload \
  -H "Authorization: Bearer <jwt-token>" \
  -F "file=@/path/to/file.csv"
```

#### 4. Complete Batch

```bash
curl -X POST http://localhost:8080/api/v1/batch/{batchId}/complete \
  -H "Authorization: Bearer <jwt-token>"
```

### Admin API (Keycloak Required)

#### Create Account

```bash
curl -X POST http://localhost:8080/admin/accounts \
  -H "Authorization: Bearer <keycloak-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "name": "Example User"
  }'
```

#### Create Site

```bash
curl -X POST http://localhost:8080/admin/accounts/{accountId}/sites \
  -H "Authorization: Bearer <keycloak-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "domain": "example.com",
    "displayName": "Example Site"
  }'
```

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
5. **File Size Limit**: 500MB per file upload

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
      maximum-pool-size: 20
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

- Follow Java 21 conventions
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

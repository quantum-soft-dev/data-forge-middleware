# Quickstart: Global Error Handling

**Feature**: 016-global-error-handling
**Date**: 2026-01-11

## Prerequisites

- Java 21, Gradle 8.x
- Docker (PostgreSQL 16, LocalStack)
- Node.js 20+, npm

## Setup

```bash
# Start dependencies
docker-compose up -d postgres localstack

# Run migrations (includes new V17 migration)
./gradlew flywayMigrate

# Start backend
./gradlew bootRun --args='--spring.profiles.active=dev'

# Start frontend (separate terminal)
cd frontend && npm run dev
```

## API Quick Reference

### Client API (Device → Server)

```bash
# Log global error (no batch)
curl -X POST http://localhost:8080/api/dfc/error \
  -H "Authorization: Bearer $SITE_JWT" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "FILE_SYSTEM_ERROR",
    "message": "Cannot access /data directory: Permission denied",
    "severity": "CRITICAL",
    "metadata": {"path": "/data", "errno": "EACCES"}
  }'

# Log error with default severity (ERROR)
curl -X POST http://localhost:8080/api/dfc/error \
  -H "Authorization: Bearer $SITE_JWT" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "CONFIG_ERROR",
    "message": "Invalid configuration file format"
  }'
```

### User API (Dashboard)

```bash
# Get AUTH0 token first (use your Auth0 credentials)
export AUTH_TOKEN="..."

# List global errors (paginated)
curl http://localhost:8080/api/v1/account/errors?page=0&size=20 \
  -H "Authorization: Bearer $AUTH_TOKEN"

# Get unread count (for badge)
curl http://localhost:8080/api/v1/account/errors/unread-count \
  -H "Authorization: Bearer $AUTH_TOKEN"

# Mark single error as read
curl -X PATCH http://localhost:8080/api/v1/account/errors/{errorId}/read \
  -H "Authorization: Bearer $AUTH_TOKEN"

# Mark multiple as read
curl -X POST http://localhost:8080/api/v1/account/errors/mark-as-read \
  -H "Authorization: Bearer $AUTH_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"errorIds": ["uuid1", "uuid2", "uuid3"]}'

# Mark all as read
curl -X POST http://localhost:8080/api/v1/account/errors/mark-all-as-read \
  -H "Authorization: Bearer $AUTH_TOKEN"
```

## Key Files

### Backend

| File | Purpose |
|------|---------|
| `error/domain/ErrorSeverity.java` | Severity enum |
| `error/domain/ErrorLog.java` | Entity with new fields |
| `error/domain/ErrorLogRepository.java` | Repository with new queries |
| `error/presentation/GlobalErrorUserController.java` | User-facing API |
| `error/presentation/dto/GlobalErrorResponseDto.java` | Response DTO |
| `db/migration/V17__add_severity_and_is_read_to_error_logs.sql` | Schema migration |

### Frontend

| File | Purpose |
|------|---------|
| `features/global-errors/api/global-errors.api.ts` | API client |
| `features/global-errors/api/global-errors.queries.ts` | TanStack Query hooks |
| `features/global-errors/model/global-error.types.ts` | TypeScript types |
| `widgets/global-errors/GlobalErrorsWidget.tsx` | Dashboard widget |

## Testing

```bash
# Run unit tests
./gradlew test --tests "*GlobalError*"

# Run integration tests
./gradlew integrationTest --tests "*GlobalError*"

# Run contract tests
./gradlew test --tests "*GlobalErrorUserControllerTest"

# Frontend tests
cd frontend && npm test -- --grep "GlobalError"
```

## Verification Checklist

- [ ] Migration applied: `SELECT column_name FROM information_schema.columns WHERE table_name = 'error_logs' AND column_name IN ('severity', 'is_read')`
- [ ] Existing errors have defaults: `SELECT severity, is_read FROM error_logs LIMIT 5`
- [ ] Client can log error with severity
- [ ] User can list global errors (Dashboard)
- [ ] Unread count returns correct value
- [ ] Mark as read updates is_read flag
- [ ] Dashboard widget displays badge

## Common Issues

### Migration fails on partitioned table
Ensure PostgreSQL 11+ is used. Partitioned tables require special handling for ALTER TABLE.

### Auth0 token missing accountId
Check Auth0 Rules/Actions add `https://api.dataforge.com/accountId` claim.

### Count query slow
Verify partial index exists: `idx_error_logs_global_unread`

## Response Examples

### List Global Errors

```json
{
  "content": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "siteId": "123e4567-e89b-12d3-a456-426614174000",
      "siteName": "Production Site",
      "type": "FILE_SYSTEM_ERROR",
      "title": "FILE_SYSTEM_ERROR",
      "severity": "CRITICAL",
      "isRead": false,
      "occurredAt": "2026-01-11T10:30:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

### Unread Count

```json
{
  "count": 7
}
```

### Mark as Read Response

```json
{
  "markedCount": 5
}
```

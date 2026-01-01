# Quickstart: Plugin History Management

**Feature**: 014-plugin-history
**Date**: 2026-01-01

## Prerequisites

- Java 21
- Docker (for PostgreSQL + LocalStack)
- Node.js 18+ (for frontend)

## Local Setup

```bash
# Start dependencies
docker-compose up -d postgres localstack

# Run migrations
./gradlew flywayMigrate

# Start backend
./gradlew bootRun --args='--spring.profiles.active=dev'

# Start frontend (separate terminal)
cd frontend && npm run dev
```

## API Examples

### 1. List SQL Generations

```bash
curl -X GET "http://localhost:8080/api/v1/admin/plugins/bit-bi/accounts/{accountId}/generations?page=0&size=20" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}"
```

Response:
```json
{
  "content": [
    {
      "id": "abc12345-...",
      "sourceBatchId": "batch-123-...",
      "siteDomain": "admin-site.example.com",
      "createdAt": "2026-01-01T12:00:00Z",
      "statementCount": 150,
      "insertCount": 100,
      "updateCount": 30,
      "deleteCount": 20,
      "isInitialLoad": false,
      "superseded": false
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 42,
  "totalPages": 3
}
```

### 2. View SQL Content (Paginated)

```bash
curl -X GET "http://localhost:8080/api/v1/admin/plugins/bit-bi/accounts/{accountId}/generations/{generationId}/content?page=0&size=100" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}"
```

Response:
```json
{
  "generationId": "abc12345-...",
  "page": 0,
  "pageSize": 100,
  "totalPages": 2,
  "totalStatements": 150,
  "statements": [
    "INSERT INTO customers (id, name) VALUES ('1', 'Alice');",
    "UPDATE users SET status = 'inactive' WHERE id = '2';",
    "..."
  ],
  "hasNext": true,
  "hasPrevious": false
}
```

### 3. Download SQL File

```bash
curl -X GET "http://localhost:8080/api/v1/admin/plugins/bit-bi/accounts/{accountId}/generations/{generationId}/download" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -o generation.sql
```

### 4. Get Clear Summary

```bash
curl -X GET "http://localhost:8080/api/v1/admin/plugins/bit-bi/accounts/{accountId}/history/summary" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}"
```

Response:
```json
{
  "accountId": "account-123-...",
  "pluginId": "bit-bi",
  "generationCount": 42,
  "totalFileSizeBytes": 1234567,
  "pluginWillBeDeactivated": true,
  "hasActiveBatches": false
}
```

### 5. Clear History

```bash
curl -X DELETE "http://localhost:8080/api/v1/admin/plugins/bit-bi/accounts/{accountId}/history?confirm=true" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}"
```

Response:
```json
{
  "deletedGenerations": 42,
  "deletedFilesCount": 42,
  "deletedTotalBytes": 1234567,
  "failedS3Keys": [],
  "pluginDeactivated": true,
  "clearedAt": "2026-01-01T15:30:00Z"
}
```

### 6. Regenerate SQL

```bash
curl -X POST "http://localhost:8080/api/v1/admin/plugins/bit-bi/accounts/{accountId}/generations/{generationId}/regenerate" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}"
```

Response:
```json
{
  "originalGenerationId": "old-gen-123-...",
  "newGenerationId": "new-gen-456-...",
  "statementCount": 155,
  "insertCount": 105,
  "updateCount": 30,
  "deleteCount": 20,
  "generationDurationMs": 234,
  "regeneratedAt": "2026-01-01T16:00:00Z"
}
```

## Testing

### Run Unit Tests

```bash
./gradlew test --tests "*PluginHistoryServiceTest*"
```

### Run Integration Tests

```bash
./gradlew integrationTest --tests "*PluginHistoryIntegrationTest*"
```

### Run Contract Tests

```bash
./gradlew test --tests "*PluginHistoryAdminControllerTest*"
```

### Frontend Tests

```bash
cd frontend && npm test -- --grep "PluginHistory"
```

## Key Files

| File | Purpose |
|------|---------|
| `PluginHistoryService.java` | Core business logic for view/clear/regenerate |
| `PluginAdminController.java` | Admin endpoints (extended) |
| `PluginSqlGeneration.java` | Entity with superseded fields |
| `V14__add_plugin_history_fields.sql` | Database migration |
| `PluginHistoryWidget.tsx` | Frontend history view component |
| `SqlPreview.tsx` | SQL content viewer with syntax highlighting |

## Common Issues

### 1. 403 Forbidden

Ensure your JWT token has `ROLE_ADMIN` claim.

### 2. 404 Account Not Found

Verify the account has an active plugin connection (`account_plugins` record exists).

### 3. Clear Fails with Active Batches

Check for batches in `PROCESSING` status. Wait for completion or manually fail them.

### 4. S3 Files Not Deleting

Check LocalStack is running and S3 bucket exists. Failed keys are returned in response for manual cleanup.

# Quickstart: BitBi Plugin Reinit Option

**Feature**: 015-plugin-reinit
**Date**: 2026-01-04

## Overview

This feature adds two capabilities to the BitBi plugin:
1. **Automatic SQL initialization** on plugin activation
2. **Manual reinit endpoint** to clear and regenerate SQL state

## Prerequisites

- Java 21 LTS
- Running PostgreSQL 16 instance
- Running LocalStack (for S3 in development)
- Auth0 configuration for OAuth2

## Development Setup

```bash
# Start dependencies
docker-compose up postgres localstack -d

# Run the application
./gradlew bootRun --args='--spring.profiles.active=dev'
```

## Testing the Feature

### 1. Test Automatic Initialization on Activation

```bash
# First, ensure you have a completed batch for your account
# (Upload files via the client API)

# Activate the BitBi plugin
curl -X POST "http://localhost:8080/api/v1/plugins/bit-bi/activate" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"tenantId": "my-tenant"}'

# Response includes API key (shown only once)
# SQL generation starts automatically in background

# Check plugin logs for SQL generation status
curl "http://localhost:8080/api/v1/account/plugins/bit-bi/logs" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

### 2. Test Reinit Endpoint

```bash
# Reinitialize plugin SQL state
curl -X POST "http://localhost:8080/api/v1/account/plugins/bit-bi/reinit" \
  -H "Authorization: Bearer $ACCESS_TOKEN"

# Response:
# {
#   "success": true,
#   "deletedGenerations": 5,
#   "deletedS3Files": 5,
#   "sqlGenerationTriggered": true,
#   "batchId": "550e8400-e29b-41d4-a716-446655440000",
#   "message": "Plugin reinitialized. SQL generation running asynchronously."
# }
```

### 3. Verify SQL Changes Available

```bash
# Use the plugin API to fetch SQL changes
curl "http://localhost:8080/api/v1/plugins/bit-bi/sql-changes?siteId=$SITE_ID&since=2020-01-01T00:00:00Z" \
  -H "X-Plugin-Api-Key: $PLUGIN_API_KEY"
```

## Running Tests

```bash
# Unit tests
./gradlew test --tests "com.bitbi.dfm.plugin.unit.*"

# Integration tests (requires Docker)
./gradlew integrationTest --tests "com.bitbi.dfm.plugin.integration.*"

# Contract tests
./gradlew test --tests "com.bitbi.dfm.plugin.contract.*"
```

## Key Files Modified

| File | Change |
|------|--------|
| `BitBiPlugin.java` | Add batch lookup + async SQL generation on activation |
| `PluginHistoryService.java` | Add `reinit()` method |
| `AccountPluginsController.java` | Add `POST /{pluginId}/reinit` endpoint |
| `BatchRepository.java` | Add `findLatestCompletedByAccountId()` |
| `PluginActionType.java` | Add `REINIT` enum value |

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/plugins/{pluginId}/activate` | Activate plugin (now triggers SQL init) |
| POST | `/api/v1/account/plugins/{pluginId}/reinit` | Reinitialize plugin SQL state |
| GET | `/api/v1/account/plugins/{pluginId}/logs` | View plugin audit logs |

## Troubleshooting

### SQL generation not triggered

- Check if completed batches exist for the account
- View plugin logs for error messages
- Ensure async executor is configured

### Reinit returns 400 Bad Request

- Plugin must be active to reinit
- Check `isActive` status in account_plugins table

### S3 files not deleted

- Check LocalStack is running
- Verify S3 bucket configuration
- S3 deletion failures are logged but don't fail the operation

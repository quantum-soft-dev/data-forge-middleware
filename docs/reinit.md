# Plugin Reinit Endpoint

## Overview

The `/reinit` endpoint allows users to reinitialize their plugin SQL state by clearing all existing SQL generation history and re-baselining on the most recent completed batch (the client re-downloads that baseline via the files endpoint; for V2 sites the per-table delta SQL baselines are recaptured, 026). Unlike clearing history, reinit preserves the API key and plugin configuration. Reinit no longer triggers SQL generation — the async regeneration this endpoint originally performed (feature 015) was removed, and `sqlGenerationTriggered` is always `false` (kept for API compatibility).

## Endpoint

```
POST /api/v1/account/plugins/{pluginId}/reinit
```

## Authentication

This endpoint requires OAuth2 authentication with a valid user JWT token.

```http
Authorization: Bearer your-jwt-token
```

## Request

### Headers

| Header | Required | Description |
|--------|----------|-------------|
| `Authorization` | Yes | Bearer token for OAuth2 authentication |

### Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `pluginId` | String | Yes | Plugin identifier (e.g., `bit-bi`) |

### Request Body

None.

## Response

### Success Response (200 OK)

```json
{
  "success": true,
  "deletedGenerations": 15,
  "deletedS3Files": 15,
  "totalBytesFreed": 524288,
  "sqlGenerationTriggered": false,
  "batchId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "message": "Plugin reinitialized. New baseline batch set. Client should download CSV files via /sites/{siteId}/files endpoint.",
  "s3DeleteWarnings": []
}
```

### Response Fields

| Field | Type | Description |
|-------|------|-------------|
| `success` | Boolean | Whether the reinit operation completed successfully |
| `deletedGenerations` | Long | Number of SQL generation records deleted from database |
| `deletedS3Files` | Long | Number of S3 files successfully deleted |
| `totalBytesFreed` | Long | Total storage freed in bytes |
| `sqlGenerationTriggered` | Boolean | Always `false` — reinit no longer triggers SQL generation (field kept for API compatibility) |
| `batchId` | UUID | ID of the batch set as the new baseline (null if no completed batches) |
| `message` | String | Human-readable status message |
| `s3DeleteWarnings` | Array | List of S3 keys that failed to delete (best-effort deletion) |

### No Batches Response

If no completed batches exist for the account:

```json
{
  "success": true,
  "deletedGenerations": 5,
  "deletedS3Files": 5,
  "totalBytesFreed": 102400,
  "sqlGenerationTriggered": false,
  "batchId": null,
  "message": "Plugin reinitialized. No completed batches found. First future batch will become baseline.",
  "s3DeleteWarnings": []
}
```

## What Happens During Reinit

1. **Validation**: Verifies the plugin is active for the account
2. **S3 Deletion**: Deletes all SQL files from S3 (best-effort, continues on failure)
3. **Database Cleanup**: Removes all SQL generation records from the database
4. **Re-baseline**: Sets the latest completed batch as the new baseline (or clears it when none exists, so the first future batch becomes the baseline); for V2 sites, recaptures the per-table delta SQL baselines from current checkpoints and re-enqueues the segments above them (026). No SQL generation is triggered.
5. **Audit Logging**: Records the reinit operation in the audit trail

## Key Differences from Clear History

| Aspect | Reinit | Clear History |
|--------|--------|---------------|
| **API Key** | Preserved | Lost (plugin deactivated) |
| **Plugin Status** | Remains active | Deactivated |
| **SQL Regeneration** | None (re-baseline only; the removed 015 flow used to regenerate) | None |
| **Use Case** | Refresh data | Complete reset |

## Error Responses

### 400 Bad Request

Returned when the plugin is not active.

```json
{
  "timestamp": "2025-01-15T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Plugin 'bit-bi' is not active for this account",
  "path": "/api/v1/account/plugins/bit-bi/reinit"
}
```

### 401 Unauthorized

Returned when the JWT token is missing or invalid.

```json
{
  "timestamp": "2025-01-15T10:30:00Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Full authentication is required to access this resource",
  "path": "/api/v1/account/plugins/bit-bi/reinit"
}
```

### 429 Too Many Requests

Returned when rate limit is exceeded (1 request per 30 seconds).

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 30
```

## Rate Limiting

This endpoint is rate-limited to **1 request per 30 seconds** per account due to its resource-intensive nature (S3 deletions, SQL generation). When exceeded, a `429 Too Many Requests` response is returned with a `Retry-After` header.

## Use Cases

1. **Data Drift**: Your source data has changed significantly and you need a fresh SQL baseline
2. **Troubleshooting**: SQL generation issues that require a clean slate
3. **Testing**: Reset plugin state without changing your API key configuration

## Examples

### cURL

```bash
curl -X POST "https://api.dataforge.com/api/v1/account/plugins/bit-bi/reinit" \
  -H "Authorization: Bearer your-jwt-token"
```

### JavaScript (fetch)

```javascript
const response = await fetch(
  'https://api.dataforge.com/api/v1/account/plugins/bit-bi/reinit',
  {
    method: 'POST',
    headers: {
      'Authorization': 'Bearer your-jwt-token'
    }
  }
);

if (response.status === 429) {
  const retryAfter = response.headers.get('Retry-After');
  console.log(`Rate limited. Retry after ${retryAfter} seconds`);
} else {
  const data = await response.json();
  console.log(`Deleted ${data.deletedGenerations} generations`);
  console.log(`New baseline batch: ${data.batchId}`);
}
```

### Python (requests)

```python
import requests

response = requests.post(
    'https://api.dataforge.com/api/v1/account/plugins/bit-bi/reinit',
    headers={'Authorization': 'Bearer your-jwt-token'}
)

if response.status_code == 429:
    retry_after = response.headers.get('Retry-After')
    print(f"Rate limited. Retry after {retry_after} seconds")
elif response.status_code == 200:
    data = response.json()
    print(f"Deleted {data['deletedGenerations']} generations")
    print(f"Freed {data['totalBytesFreed']} bytes")
```

## Related Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /api/v1/account/plugins` | List active plugins for the account |
| `GET /api/v1/account/plugins/{pluginId}/logs` | View plugin activity logs |
| `PUT /api/v1/plugins/{pluginId}/activate` | Activate or update plugin |

## Swagger/OpenAPI

This endpoint is documented in the OpenAPI specification available at:
- Swagger UI: `/swagger-ui.html`
- OpenAPI JSON: `/v3/api-docs`

## See Also

- [Bit BI Integration Guide](./bitbi-integration.md)
- [Plugin System Documentation](./plugin-system-functinal.md)

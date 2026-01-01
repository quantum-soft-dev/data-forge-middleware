# Bit BI Plugin API: Tables Endpoint

## Overview

The `/tables` endpoint provides a list of all unique tables (uploaded CSV files) for an account. This endpoint is part of the Bit BI Plugin API and is used to discover available tables before querying SQL changes.

## Endpoint

```
GET /api/v1/plugins/bit-bi/tables
```

## Authentication

This endpoint requires Plugin API Key authentication via the `X-Plugin-Api-Key` header.

```http
X-Plugin-Api-Key: your-api-key-here
```

The API key is generated when the Bit BI plugin is activated for an account. It is returned only once during activation and should be stored securely.

## Request

### Headers

| Header | Required | Description |
|--------|----------|-------------|
| `X-Plugin-Api-Key` | Yes | Plugin API key for authentication |

### Query Parameters

None.

## Response

### Success Response (200 OK)

```json
{
  "tables": [
    {
      "tableName": "customers",
      "fileSize": 1048576,
      "lastUpdatedAt": "2025-01-15T10:30:00Z"
    },
    {
      "tableName": "orders",
      "fileSize": 2097152,
      "lastUpdatedAt": "2025-01-15T09:15:00Z"
    }
  ]
}
```

### Response Fields

| Field | Type | Description |
|-------|------|-------------|
| `tables` | Array | List of table objects |
| `tables[].tableName` | String | Table name derived from original file name (without extension) |
| `tables[].fileSize` | Long | Size of the latest uploaded file in bytes |
| `tables[].lastUpdatedAt` | String | ISO8601 timestamp of the most recent upload |

### Empty Response

If no files have been uploaded for the account:

```json
{
  "tables": []
}
```

## Table Name Derivation

Table names are derived from original uploaded file names using the following rules:

1. **Extension removal**: `.csv.gz` and `.csv` extensions are stripped
2. **Character sanitization**: Invalid characters (anything except `a-z`, `A-Z`, `0-9`, `_`) are replaced with underscore
3. **Digit prefix**: If the name starts with a digit, an underscore is prepended (SQL table names cannot start with digits)

### Examples

| Original File Name | Derived Table Name |
|-------------------|-------------------|
| `customers.csv` | `customers` |
| `customers.csv.gz` | `customers` |
| `123_data.csv` | `_123_data` |
| `my-orders.csv` | `my_orders` |
| `sales report.csv` | `sales_report` |
| `2024_Q1_report.csv.gz` | `_2024_Q1_report` |

## Error Responses

### 401 Unauthorized

Returned when the API key is missing or invalid.

```json
{
  "timestamp": "2025-01-15T10:30:00Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Invalid or missing API key",
  "path": "/api/v1/plugins/bit-bi/tables"
}
```

### 429 Too Many Requests

Returned when the rate limit is exceeded.

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 60
```

## Rate Limiting

This endpoint is subject to rate limiting. The default limit is **100 requests per minute** per account. When exceeded, a `429 Too Many Requests` response is returned with a `Retry-After` header indicating how many seconds to wait before retrying.

## Examples

### cURL

```bash
curl -X GET "https://api.dataforge.com/api/v1/plugins/bit-bi/tables" \
  -H "X-Plugin-Api-Key: your-api-key-here"
```

### JavaScript (fetch)

```javascript
const response = await fetch('https://api.dataforge.com/api/v1/plugins/bit-bi/tables', {
  method: 'GET',
  headers: {
    'X-Plugin-Api-Key': 'your-api-key-here'
  }
});

const data = await response.json();
console.log(data.tables);
```

### Python (requests)

```python
import requests

response = requests.get(
    'https://api.dataforge.com/api/v1/plugins/bit-bi/tables',
    headers={'X-Plugin-Api-Key': 'your-api-key-here'}
)

data = response.json()
for table in data['tables']:
    print(f"{table['tableName']}: {table['fileSize']} bytes")
```

## Related Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /api/v1/plugins/bit-bi/sites` | List available sites for the account |
| `GET /api/v1/plugins/bit-bi/sql-changes` | Get SQL changes for a specific site |

## Swagger/OpenAPI

This endpoint is documented in the OpenAPI specification available at:
- Swagger UI: `/swagger-ui.html`
- OpenAPI JSON: `/v3/api-docs`

## See Also

- [Bit BI Integration Guide](./bitbi-integration.md)
- [Plugin System Documentation](./plugin-system-functinal.md)

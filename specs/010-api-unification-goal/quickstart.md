# Quick Start: API Unification Migration Guide

**Feature**: API Unification
**Date**: 2025-11-05
**Audience**: Frontend developers, device client developers, API consumers

## Overview

This guide helps you migrate from the old API endpoint structure to the new unified API structure. All functionality remains identical - only endpoint paths and authentication routing change.

**Migration Strategy**: Coordinated deployment - all client systems must update simultaneously. Old endpoints return 410 Gone immediately after backend deployment.

---

## What's Changing

### Two Distinct APIs

**Before**: Endpoints scattered across `/api/dfc/*`, `/api/admin/*`, `/api/user/*`, `/api/v1/*` (inconsistent)

**After**: Two clearly separated APIs:

1. **Device API** (`/api/v1/device/*`) - For client devices
   - Authentication: Custom JWT (site credentials)
   - Use case: IoT devices, mobile apps, data collection clients

2. **UI/Admin API** (`/api/v1/*`) - For web interface
   - Authentication: Keycloak OAuth2
   - Use case: Admin dashboard, user portal

---

## Complete Endpoint Mapping

### Device API Endpoints (Custom JWT)

| Old Endpoint | New Endpoint | Method | Purpose |
|--------------|--------------|--------|---------|
| `POST /api/v1/auth/token` | `POST /api/v1/device/auth/token` | POST | Generate JWT token |
| `POST /api/dfc/batch/start` | `POST /api/v1/device/batches/start` | POST | Start new batch |
| `POST /api/dfc/batch/{id}/complete` | `POST /api/v1/device/batches/{id}/complete` | POST | Complete batch |
| `POST /api/dfc/batch/{id}/fail` | `POST /api/v1/device/batches/{id}/fail` | POST | Mark batch failed |
| `POST /api/dfc/batch/{id}/cancel` | `POST /api/v1/device/batches/{id}/cancel` | POST | Cancel batch |
| `GET /api/dfc/batch/{id}` | `GET /api/v1/device/batches/{id}` | GET | Get batch details |
| `POST /api/dfc/batch/{batchId}/upload` | `POST /api/v1/device/files/batches/{batchId}/upload` | POST | Upload file |
| `GET /api/dfc/batch/{batchId}/files/{fileId}` | `GET /api/v1/device/files/batches/{batchId}/files/{fileId}` | GET | Get file metadata |
| `POST /api/dfc/error` | `POST /api/v1/device/errors` | POST | Log standalone error |
| `POST /api/dfc/error/{batchId}` | `POST /api/v1/device/errors/batches/{batchId}` | POST | Log batch error |
| `GET /api/dfc/error/log/{errorId}` | `GET /api/v1/device/errors/{errorId}` | GET | Get error details |

### UI/Admin API Endpoints (Keycloak OAuth2)

#### Accounts

| Old Endpoint | New Endpoint | Method | Purpose |
|--------------|--------------|--------|---------|
| `GET /api/admin/accounts` | `GET /api/v1/accounts` | GET | List accounts |
| `POST /api/admin/accounts` | `POST /api/v1/accounts` | POST | Create account |
| `GET /api/admin/accounts/with-keycloak` | `GET /api/v1/accounts/with-keycloak` | GET | List Keycloak accounts |
| `POST /api/admin/accounts/with-keycloak` | `POST /api/v1/accounts/with-keycloak` | POST | Create Keycloak account |
| `GET /api/admin/accounts/{id}` | `GET /api/v1/accounts/{id}` | GET | Get account |
| `PUT /api/admin/accounts/{id}` | `PUT /api/v1/accounts/{id}` | PUT | Update account |
| `DELETE /api/admin/accounts/{id}` | `DELETE /api/v1/accounts/{id}` | DELETE | Delete account |
| `POST /api/admin/accounts/{id}/lock` | `POST /api/v1/accounts/{id}/lock` | POST | Lock account |
| `POST /api/admin/accounts/{id}/unlock` | `POST /api/v1/accounts/{id}/unlock` | POST | Unlock account |
| `POST /api/admin/accounts/{id}/reset-password` | `POST /api/v1/accounts/{id}/reset-password` | POST | Reset password |
| `GET /api/admin/accounts/{id}/audit-logs` | `GET /api/v1/accounts/{id}/audit-logs` | GET | Get audit logs |

#### Sites

| Old Endpoint | New Endpoint | Method | Purpose |
|--------------|--------------|--------|---------|
| `GET /api/admin/sites` | `GET /api/v1/sites` | GET | List sites |
| `POST /api/admin/accounts/{accountId}/sites` | `POST /api/v1/accounts/{accountId}/sites` | POST | Create site |
| `GET /api/admin/accounts/{accountId}/sites` | `GET /api/v1/accounts/{accountId}/sites` | GET | List account sites |
| `GET /api/admin/sites/{id}` | `GET /api/v1/sites/{id}` | GET | Get site |
| `PUT /api/admin/sites/{id}` | `PUT /api/v1/sites/{id}` | PUT | Update site |
| `DELETE /api/admin/sites/{id}` | `DELETE /api/v1/sites/{id}` | DELETE | Delete site |
| `POST /api/admin/accounts/{accountId}/sites/{siteId}/activate` | `POST /api/v1/accounts/{accountId}/sites/{siteId}/activate` | POST | Activate site |
| `POST /api/admin/accounts/{accountId}/sites/{siteId}/deactivate` | `POST /api/v1/accounts/{accountId}/sites/{siteId}/deactivate` | POST | Deactivate site |
| `DELETE /api/admin/accounts/{accountId}/sites/{siteId}` | `DELETE /api/v1/accounts/{accountId}/sites/{siteId}` | DELETE | Delete site (alt) |
| `GET /api/admin/sites/{id}/statistics` | `GET /api/v1/sites/{id}/statistics` | GET | Get site stats |

#### Batches (Admin)

| Old Endpoint | New Endpoint | Method | Purpose |
|--------------|--------------|--------|---------|
| `GET /api/admin/batches` | `GET /api/v1/batches` | GET | List batches (admin) |
| `GET /api/admin/batches/{id}` | `GET /api/v1/batches/{id}` | GET | Get batch (admin) |
| `DELETE /api/admin/batches/{id}` | `DELETE /api/v1/batches/{id}` | DELETE | Delete batch |

#### Upload History (User)

| Old Endpoint | New Endpoint | Method | Purpose |
|--------------|--------------|--------|---------|
| `GET /api/user/batches` | `GET /api/v1/history/batches` | GET | List upload history |
| `GET /api/user/batches/{batchId}` | `GET /api/v1/history/batches/{batchId}` | GET | Get batch details |
| `GET /api/user/batches/{batchId}/files/{fileId}/download` | `GET /api/v1/history/batches/{batchId}/files/{fileId}/download` | GET | Download file |
| `POST /api/user/batches/{batchId}/download-zip` | `POST /api/v1/history/batches/{batchId}/download-zip` | POST | Download ZIP |
| `POST /api/user/batches/{batchId}/export-excel` | `POST /api/v1/history/batches/{batchId}/export-excel` | POST | Export Excel |
| `GET /api/user/batches/{batchId}/errors` | `GET /api/v1/history/batches/{batchId}/errors` | GET | Get batch errors |

#### Errors (Admin)

| Old Endpoint | New Endpoint | Method | Purpose |
|--------------|--------------|--------|---------|
| `GET /api/admin/errors` | `GET /api/v1/errors` | GET | List errors |
| `GET /api/admin/errors/export` | `GET /api/v1/errors/export` | GET | Export errors |

#### Comparisons (Unchanged)

| Old Endpoint | New Endpoint | Method | Purpose |
|--------------|--------------|--------|---------|
| `POST /api/v1/comparisons` | `POST /api/v1/comparisons` | POST | Create comparison (no change) |
| `GET /api/v1/comparisons` | `GET /api/v1/comparisons` | GET | List comparisons (no change) |
| `GET /api/v1/comparisons/{id}` | `GET /api/v1/comparisons/{id}` | GET | Get comparison (no change) |
| `GET /api/v1/comparisons/{id}/results` | `GET /api/v1/comparisons/{id}/results` | GET | Get results (no change) |
| `GET /api/v1/comparisons/{id}/summary` | `GET /api/v1/comparisons/{id}/summary` | GET | Get summary (no change) |
| `GET /api/v1/comparisons/by-batch/{batchId}` | `GET /api/v1/comparisons/by-batch/{batchId}` | GET | Get by batch (no change) |
| `DELETE /api/v1/comparisons/{id}` | `DELETE /api/v1/comparisons/{id}` | DELETE | Delete comparison (no change) |
| `GET /api/v1/comparisons/{id}/download` | `GET /api/v1/comparisons/{id}/download` | GET | Download ZIP (no change) |
| `GET /api/v1/comparisons/{id}/summary/download` | `GET /api/v1/comparisons/{id}/summary/download` | GET | Download summary (no change) |

---

## Migration Code Examples

### Example 1: Device Client (IoT/Mobile) - Token Generation

**Old Code**:
```java
// Old endpoint
String tokenUrl = "https://api.example.com/api/v1/auth/token";

HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create(tokenUrl))
    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
        (domain + ":" + clientSecret).getBytes()))
    .POST(HttpRequest.BodyPublishers.noBody())
    .build();

HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
```

**New Code**:
```java
// New endpoint - only URL changed
String tokenUrl = "https://api.example.com/api/v1/device/auth/token";

HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create(tokenUrl))
    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
        (domain + ":" + clientSecret).getBytes()))
    .POST(HttpRequest.BodyPublishers.noBody())
    .build();

HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
```

**What Changed**: Only the URL path. Request format, authentication, and response format remain identical.

---

### Example 2: Device Client - Start Batch

**Old Code**:
```java
String batchUrl = "https://api.example.com/api/dfc/batch/start";

String requestBody = "{\"siteId\":\"" + siteId + "\"}";

HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create(batchUrl))
    .header("Authorization", "Bearer " + jwtToken)
    .header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
    .build();

HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
```

**New Code**:
```java
String batchUrl = "https://api.example.com/api/v1/device/batches/start";

String requestBody = "{\"siteId\":\"" + siteId + "\"}";

HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create(batchUrl))
    .header("Authorization", "Bearer " + jwtToken)
    .header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
    .build();

HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
```

**What Changed**: Only the URL path. JWT token format, request payload, and response format remain identical.

---

### Example 3: Frontend (React) - Account Management

**Old Code**:
```typescript
// Old endpoint
const ACCOUNTS_API = '/api/admin/accounts';

export const useAccounts = () => {
  return useQuery({
    queryKey: ['accounts'],
    queryFn: async () => {
      const response = await axios.get(ACCOUNTS_API, {
        headers: {
          Authorization: `Bearer ${keycloakToken}`
        }
      });
      return response.data;
    }
  });
};
```

**New Code**:
```typescript
// New endpoint - only URL changed
const ACCOUNTS_API = '/api/v1/accounts';

export const useAccounts = () => {
  return useQuery({
    queryKey: ['accounts'],
    queryFn: async () => {
      const response = await axios.get(ACCOUNTS_API, {
        headers: {
          Authorization: `Bearer ${keycloakToken}`
        }
      });
      return response.data;
    }
  });
};
```

**What Changed**: Only the URL path. Keycloak token, request headers, and response format remain identical.

---

### Example 4: Frontend - Upload History

**Old Code**:
```typescript
const HISTORY_API = '/api/user/batches';

export const useUploadHistory = (cursor?: string) => {
  return useQuery({
    queryKey: ['uploadHistory', cursor],
    queryFn: async () => {
      const url = cursor ? `${HISTORY_API}?cursor=${cursor}` : HISTORY_API;
      const response = await axios.get(url, {
        headers: {
          Authorization: `Bearer ${keycloakToken}`
        }
      });
      return response.data;
    }
  });
};
```

**New Code**:
```typescript
const HISTORY_API = '/api/v1/history/batches';

export const useUploadHistory = (cursor?: string) => {
  return useQuery({
    queryKey: ['uploadHistory', cursor],
    queryFn: async () => {
      const url = cursor ? `${HISTORY_API}?cursor=${cursor}` : HISTORY_API;
      const response = await axios.get(url, {
        headers: {
          Authorization: `Bearer ${keycloakToken}`
        }
      });
      return response.data;
    }
  });
};
```

**What Changed**: Only the URL path. Authentication, query parameters, and response format remain identical.

---

## Configuration File Updates

### Device Client Configuration

**Old**:
```properties
# application.properties
api.base.url=https://api.example.com
api.auth.path=/api/v1/auth/token
api.batch.path=/api/dfc/batch
api.upload.path=/api/dfc/batch/{batchId}/upload
api.error.path=/api/dfc/error
```

**New**:
```properties
# application.properties
api.base.url=https://api.example.com
api.auth.path=/api/v1/device/auth/token
api.batch.path=/api/v1/device/batches
api.upload.path=/api/v1/device/files/batches/{batchId}/upload
api.error.path=/api/v1/device/errors
```

### Frontend Environment Variables

**Old**:
```env
# .env
VITE_API_BASE_URL=https://api.example.com
VITE_API_ACCOUNTS=/api/admin/accounts
VITE_API_SITES=/api/admin/sites
VITE_API_HISTORY=/api/user/batches
VITE_API_ERRORS=/api/admin/errors
```

**New**:
```env
# .env
VITE_API_BASE_URL=https://api.example.com
VITE_API_ACCOUNTS=/api/v1/accounts
VITE_API_SITES=/api/v1/sites
VITE_API_HISTORY=/api/v1/history/batches
VITE_API_ERRORS=/api/v1/errors
```

---

## Error Handling: 410 Gone Responses

After backend deployment, old endpoint paths return 410 Gone with migration guidance:

**Example Response**:
```json
{
  "timestamp": "2025-11-05T14:30:00Z",
  "status": 410,
  "error": "Gone",
  "message": "This endpoint has been removed. Please use: /api/v1/device/batches/start",
  "path": "/api/dfc/batch/start"
}
```

**Client Handling**:
```java
if (response.statusCode() == 410) {
    String errorBody = response.body();
    JsonObject error = JsonParser.parseString(errorBody).getAsJsonObject();
    String newPath = error.get("message").getAsString();

    // Extract new path from message
    // Format: "This endpoint has been removed. Please use: /api/v1/device/batches/start"
    String[] parts = newPath.split(": ");
    if (parts.length == 2) {
        String updatedPath = parts[1];
        log.error("Endpoint deprecated. Update client to use: {}", updatedPath);
    }

    throw new EndpointDeprecatedException("Client needs updating");
}
```

---

## Testing Your Migration

### 1. Update Configuration
- Update all API base URLs in configuration files
- Search codebase for old endpoint paths (`/api/dfc/`, `/api/admin/`, `/api/user/`)

### 2. Run Integration Tests
- Device clients: Test token generation, batch operations, file uploads, error logging
- Frontend: Test account management, site management, upload history, error viewing

### 3. Verify Authentication
- Device API: Ensure Custom JWT tokens still work
- UI/Admin API: Ensure Keycloak tokens still work
- Test that wrong token types are rejected (403 Forbidden)

### 4. Check Response Formats
- All response payloads should remain identical
- DTOs unchanged: `BatchResponseDto`, `AccountResponseDto`, `ErrorLogResponseDto`, etc.

### 5. Performance Testing
- Response times should remain within ±5% of pre-migration performance
- No performance degradation expected (only path changes, no logic changes)

---

## Migration Checklist

- [ ] **Device Clients**
  - [ ] Update token generation endpoint to `/api/v1/device/auth/token`
  - [ ] Update batch management endpoints to `/api/v1/device/batches/*`
  - [ ] Update file upload endpoints to `/api/v1/device/files/*`
  - [ ] Update error logging endpoints to `/api/v1/device/errors/*`
  - [ ] Test Custom JWT authentication still works
  - [ ] Verify all operations complete successfully

- [ ] **Frontend Applications**
  - [ ] Update account management endpoints to `/api/v1/accounts/*`
  - [ ] Update site management endpoints to `/api/v1/sites/*`
  - [ ] Update batch admin endpoints to `/api/v1/batches/*`
  - [ ] Update upload history endpoints to `/api/v1/history/batches/*`
  - [ ] Update error management endpoints to `/api/v1/errors/*`
  - [ ] Verify comparisons endpoints unchanged (`/api/v1/comparisons/*`)
  - [ ] Test Keycloak OAuth2 authentication still works
  - [ ] Verify all CRUD operations work correctly

- [ ] **Configuration Files**
  - [ ] Update environment variables (.env, .properties, .yaml)
  - [ ] Update API documentation links
  - [ ] Update developer onboarding guides

- [ ] **Testing**
  - [ ] Run full integration test suite
  - [ ] Verify response formats unchanged (JSON schema validation)
  - [ ] Test authentication (both JWT and Keycloak)
  - [ ] Test error handling (including 410 Gone for old paths)
  - [ ] Performance testing (response times within ±5%)

- [ ] **Deployment**
  - [ ] Coordinate deployment window with all teams
  - [ ] Deploy backend first
  - [ ] Deploy device clients and frontend simultaneously
  - [ ] Monitor logs for 410 Gone responses (indicates old endpoint usage)
  - [ ] Verify rollback plan ready in case of issues

---

## Support & Troubleshooting

### Issue: Getting 410 Gone responses after backend deployment
**Solution**: Your client is still using old endpoint paths. Update configuration and redeploy.

### Issue: Getting 403 Forbidden responses
**Possible Causes**:
1. Using Custom JWT token for UI/Admin API endpoints (should use Keycloak)
2. Using Keycloak token for Device API endpoints (should use Custom JWT)
3. Token expired or invalid

**Solution**: Verify correct authentication method for the API you're calling.

### Issue: Response format different from before
**This should not happen!** All response DTOs remain unchanged. If you see different formats:
1. Verify you're calling the correct new endpoint
2. Check API version (should be `/api/v1/*`)
3. Report issue to backend team (potential bug)

### Issue: Performance degradation after migration
**This should not happen!** Response times should remain within ±5%. If slower:
1. Check network latency (ensure hitting same backend)
2. Verify database connection pool settings unchanged
3. Report issue to backend team for investigation

---

## Contact & Resources

- **Backend API Documentation**: https://api.example.com/swagger-ui.html
- **Migration Support**: backend-team@example.com
- **Spec File**: [spec.md](../spec.md)
- **Technical Plan**: [plan.md](../plan.md)
- **API Contracts**: [contracts/](../contracts/)

**Migration Deadline**: [TBD - coordinated deployment window]

**Rollback Plan**: If migration fails, all systems revert to previous deployment within 1 hour.

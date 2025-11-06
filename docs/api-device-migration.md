# Device API Migration Guide

**Version**: 2.0.0
**Date**: 2025-11-06
**Target**: Device Client Integrations (IoT devices, mobile apps, data collection clients)

## Overview

This document provides a complete migration guide for client devices to transition from legacy DFC API endpoints to the new unified Device API structure introduced in **Spec 010: API Unification Goal**.

### What Changed

- **Old**: `/api/dfc/**` (Data Forge Client endpoints)
- **New**: `/api/v1/device/**` (Unified Device API)

### Why This Migration

1. **Unified Structure**: All device operations now follow a consistent `/api/v1/device/*` pattern
2. **Clear Separation**: Device API (Custom JWT) vs UI/Admin API (Keycloak OAuth2) are now clearly separated
3. **Better Documentation**: OpenAPI documentation clearly groups device endpoints with appropriate security schemes
4. **Improved Security**: Security filter chains explicitly route device requests to Custom JWT authentication only

### Migration Timeline

- **Effective Date**: 2025-11-06 (Commit: `1e651f5`)
- **Deprecation**: Legacy `/api/dfc/**` endpoints are **immediately obsolete**
- **Response**: Requests to old endpoints will return **410 Gone** with migration guidance

---

## Authentication Migration

### Token Generation (POST)

**Old Endpoint**:
```
POST /api/dfc/auth/token
```

**New Endpoint**:
```
POST /api/v1/device/auth/token
```

**Request** (unchanged):
```http
POST /api/v1/device/auth/token HTTP/1.1
Authorization: Basic <base64(domain:password)>
Content-Type: application/json
```

**Response** (unchanged):
```json
{
  "token": "eyJhbGciOiJIUzI1NiIs...",
  "expiresAt": "2025-11-06T20:30:00Z",
  "siteId": "a1b2c3d4-...",
  "domain": "example-site"
}
```

**Changes**:
- ✅ Endpoint path updated
- ✅ Authentication method (Basic Auth) unchanged
- ✅ Response DTO unchanged
- ✅ Business logic unchanged

**Migration Example**:
```java
// Before
String tokenEndpoint = "https://api.example.com/api/dfc/auth/token";

// After
String tokenEndpoint = "https://api.example.com/api/v1/device/auth/token";
```

---

## Batch Operations Migration

### Start Batch (POST)

**Old Endpoint**:
```
POST /api/dfc/batch/start
```

**New Endpoint**:
```
POST /api/v1/device/batches/start
```

**Request** (unchanged):
```http
POST /api/v1/device/batches/start HTTP/1.1
Authorization: Bearer <jwt-token>
Content-Type: application/json

{
  "s3Path": "optional-custom-path"
}
```

**Response** (unchanged):
```json
{
  "id": "batch-uuid-...",
  "batchId": "batch-uuid-...",
  "siteId": "site-uuid-...",
  "status": "IN_PROGRESS",
  "s3Path": "accountId/domain/2025-11-06/12-30-00",
  "uploadedFilesCount": 0,
  "totalSize": 0,
  "hasErrors": false,
  "startedAt": "2025-11-06T12:30:00Z",
  "completedAt": null
}
```

**Changes**:
- ✅ Endpoint path updated (`/api/dfc/batch/start` → `/api/v1/device/batches/start`)
- ✅ Authentication (Custom JWT) unchanged
- ✅ Request/response DTOs unchanged
- ✅ Business logic unchanged

### Complete Batch (POST)

**Old Endpoint**:
```
POST /api/dfc/batch/{id}/complete
```

**New Endpoint**:
```
POST /api/v1/device/batches/{id}/complete
```

**Request** (unchanged):
```http
POST /api/v1/device/batches/{id}/complete HTTP/1.1
Authorization: Bearer <jwt-token>
```

**Response**: Same as start batch response with `status: "COMPLETED"` and `completedAt` timestamp

### Fail Batch (POST)

**Old Endpoint**:
```
POST /api/dfc/batch/{id}/fail
```

**New Endpoint**:
```
POST /api/v1/device/batches/{id}/fail
```

**Request** (unchanged):
```http
POST /api/v1/device/batches/{id}/fail HTTP/1.1
Authorization: Bearer <jwt-token>
Content-Type: application/json

{
  "reason": "Connection timeout during file upload"
}
```

### Cancel Batch (POST)

**Old Endpoint**:
```
POST /api/dfc/batch/{id}/cancel
```

**New Endpoint**:
```
POST /api/v1/device/batches/{id}/cancel
```

### Get Batch Details (GET)

**Old Endpoint**:
```
GET /api/dfc/batch/{id}
```

**New Endpoint**:
```
GET /api/v1/device/batches/{id}
```

**Request** (unchanged):
```http
GET /api/v1/device/batches/{id} HTTP/1.1
Authorization: Bearer <jwt-token>
```

---

## File Upload Migration

### Upload File (POST)

**Old Endpoint**:
```
POST /api/dfc/upload/{batchId}
```

**New Endpoint**:
```
POST /api/v1/device/files/batches/{batchId}/upload
```

**Request** (unchanged):
```http
POST /api/v1/device/files/batches/{batchId}/upload HTTP/1.1
Authorization: Bearer <jwt-token>
Content-Type: multipart/form-data

--boundary
Content-Disposition: form-data; name="file"; filename="data.csv"
Content-Type: text/csv

[file content]
--boundary--
```

**Response** (unchanged):
```json
{
  "id": "file-uuid-...",
  "batchId": "batch-uuid-...",
  "filename": "data.csv",
  "s3Key": "accountId/domain/2025-11-06/12-30-00/data.csv",
  "fileSize": 1024000,
  "checksum": "abc123def456...",
  "uploadedAt": "2025-11-06T12:31:00Z"
}
```

**Changes**:
- ✅ Endpoint path updated (`/api/dfc/upload/{batchId}` → `/api/v1/device/files/batches/{batchId}/upload`)
- ✅ Multipart upload unchanged
- ✅ File size limit (500MB) unchanged
- ✅ Response DTO unchanged

### Get File Metadata (GET)

**Old Endpoint**:
```
GET /api/dfc/files/{batchId}/{fileId}
```

**New Endpoint**:
```
GET /api/v1/device/files/batches/{batchId}/files/{fileId}
```

**Request** (unchanged):
```http
GET /api/v1/device/files/batches/{batchId}/files/{fileId} HTTP/1.1
Authorization: Bearer <jwt-token>
```

---

## Error Logging Migration

### Log Standalone Error (POST)

**Old Endpoint**:
```
POST /api/dfc/error
```

**New Endpoint**:
```
POST /api/v1/device/errors
```

**Request** (unchanged):
```http
POST /api/v1/device/errors HTTP/1.1
Authorization: Bearer <jwt-token>
Content-Type: application/json

{
  "type": "CONNECTION_ERROR",
  "message": "Failed to connect to S3: timeout after 30s",
  "metadata": {
    "endpoint": "s3.amazonaws.com",
    "attempt": 3,
    "lastError": "SocketTimeoutException"
  }
}
```

**Response** (unchanged):
```json
{
  "id": "error-uuid-...",
  "batchId": null,
  "severity": "ERROR",
  "message": "Failed to connect to S3: timeout after 30s",
  "source": "DEVICE",
  "metadata": {
    "endpoint": "s3.amazonaws.com",
    "attempt": 3,
    "lastError": "SocketTimeoutException"
  },
  "occurredAt": "2025-11-06T12:32:00Z"
}
```

### Log Batch-Associated Error (POST)

**Old Endpoint**:
```
POST /api/dfc/error/batch/{batchId}
```

**New Endpoint**:
```
POST /api/v1/device/errors/batches/{batchId}
```

**Request** (unchanged):
```http
POST /api/v1/device/errors/batches/{batchId} HTTP/1.1
Authorization: Bearer <jwt-token>
Content-Type: application/json

{
  "type": "FILE_UPLOAD_ERROR",
  "message": "File validation failed: checksum mismatch",
  "metadata": {
    "filename": "data.csv",
    "expectedChecksum": "abc123",
    "actualChecksum": "def456"
  }
}
```

### Get Error Details (GET)

**Old Endpoint**:
```
GET /api/dfc/error/{errorId}
```

**New Endpoint**:
```
GET /api/v1/device/errors/{errorId}
```

**Request** (unchanged):
```http
GET /api/v1/device/errors/{errorId} HTTP/1.1
Authorization: Bearer <jwt-token>
```

---

## Complete Endpoint Mapping Table

| Old Endpoint (Obsolete)                    | New Endpoint                                      | HTTP Method | Auth Type   |
|--------------------------------------------|---------------------------------------------------|-------------|-------------|
| `/api/dfc/auth/token`                      | `/api/v1/device/auth/token`                       | POST        | Basic Auth  |
| `/api/dfc/batch/start`                     | `/api/v1/device/batches/start`                    | POST        | Custom JWT  |
| `/api/dfc/batch/{id}/complete`             | `/api/v1/device/batches/{id}/complete`            | POST        | Custom JWT  |
| `/api/dfc/batch/{id}/fail`                 | `/api/v1/device/batches/{id}/fail`                | POST        | Custom JWT  |
| `/api/dfc/batch/{id}/cancel`               | `/api/v1/device/batches/{id}/cancel`              | POST        | Custom JWT  |
| `/api/dfc/batch/{id}`                      | `/api/v1/device/batches/{id}`                     | GET         | Custom JWT  |
| `/api/dfc/upload/{batchId}`                | `/api/v1/device/files/batches/{batchId}/upload`   | POST        | Custom JWT  |
| `/api/dfc/files/{batchId}/{fileId}`        | `/api/v1/device/files/batches/{batchId}/files/{fileId}` | GET   | Custom JWT  |
| `/api/dfc/error`                           | `/api/v1/device/errors`                           | POST        | Custom JWT  |
| `/api/dfc/error/batch/{batchId}`           | `/api/v1/device/errors/batches/{batchId}`         | POST        | Custom JWT  |
| `/api/dfc/error/{errorId}`                 | `/api/v1/device/errors/{errorId}`                 | GET         | Custom JWT  |

---

## Migration Checklist

### For Device Client Developers

- [ ] **Update Base URL**: Change API base path from `/api/dfc` to `/api/v1/device`
- [ ] **Update Authentication Endpoint**: Change token endpoint to `/api/v1/device/auth/token`
- [ ] **Update Batch Endpoints**: Update all batch operation paths (`/batches/start`, `/batches/{id}/complete`, etc.)
- [ ] **Update File Upload Endpoints**: Change file upload path to `/files/batches/{batchId}/upload`
- [ ] **Update Error Logging Endpoints**: Change error endpoints to `/errors` and `/errors/batches/{batchId}`
- [ ] **Test Authentication**: Verify Custom JWT tokens work with new endpoints
- [ ] **Test Batch Lifecycle**: Start → Upload → Complete/Fail/Cancel
- [ ] **Test Error Logging**: Verify both standalone and batch-associated errors
- [ ] **Update Configuration**: Update environment configs (dev, staging, prod) with new endpoint paths
- [ ] **Update Documentation**: Update internal API documentation with new paths
- [ ] **Regression Testing**: Run full test suite against new endpoints

### For DevOps/Infrastructure Teams

- [ ] **Update API Gateway Rules**: If using API gateway, update routing rules for `/api/v1/device/**`
- [ ] **Update Monitoring**: Update monitoring dashboards to track new endpoint paths
- [ ] **Update Logs**: Update log aggregation queries to include new endpoint patterns
- [ ] **Update Rate Limiting**: Ensure rate limiting applies to new endpoint paths
- [ ] **Update Firewall Rules**: If IP whitelisting exists, ensure it covers new paths
- [ ] **Update Load Balancer**: Update health check endpoints if needed
- [ ] **Update WAF Rules**: Update Web Application Firewall rules for new endpoint patterns

---

## Code Migration Examples

### Java (Android/JVM Clients)

**Before**:
```java
public class DataForgeClient {
    private static final String BASE_URL = "https://api.example.com/api/dfc";

    public String authenticate(String domain, String password) {
        String url = BASE_URL + "/auth/token";
        // ... rest of implementation
    }

    public BatchResponse startBatch() {
        String url = BASE_URL + "/batch/start";
        // ... rest of implementation
    }

    public void uploadFile(String batchId, File file) {
        String url = BASE_URL + "/upload/" + batchId;
        // ... rest of implementation
    }
}
```

**After**:
```java
public class DataForgeClient {
    private static final String BASE_URL = "https://api.example.com/api/v1/device";

    public String authenticate(String domain, String password) {
        String url = BASE_URL + "/auth/token";
        // ... rest of implementation (unchanged)
    }

    public BatchResponse startBatch() {
        String url = BASE_URL + "/batches/start";
        // ... rest of implementation (unchanged)
    }

    public void uploadFile(String batchId, File file) {
        String url = BASE_URL + "/files/batches/" + batchId + "/upload";
        // ... rest of implementation (unchanged)
    }
}
```

### Python (IoT Devices)

**Before**:
```python
class DataForgeClient:
    BASE_URL = "https://api.example.com/api/dfc"

    def authenticate(self, domain: str, password: str) -> dict:
        url = f"{self.BASE_URL}/auth/token"
        # ... rest of implementation

    def start_batch(self) -> dict:
        url = f"{self.BASE_URL}/batch/start"
        # ... rest of implementation

    def upload_file(self, batch_id: str, file_path: str) -> dict:
        url = f"{self.BASE_URL}/upload/{batch_id}"
        # ... rest of implementation
```

**After**:
```python
class DataForgeClient:
    BASE_URL = "https://api.example.com/api/v1/device"

    def authenticate(self, domain: str, password: str) -> dict:
        url = f"{self.BASE_URL}/auth/token"
        # ... rest of implementation (unchanged)

    def start_batch(self) -> dict:
        url = f"{self.BASE_URL}/batches/start"
        # ... rest of implementation (unchanged)

    def upload_file(self, batch_id: str, file_path: str) -> dict:
        url = f"{self.BASE_URL}/files/batches/{batch_id}/upload"
        # ... rest of implementation (unchanged)
```

### JavaScript/TypeScript (Node.js Clients)

**Before**:
```typescript
class DataForgeClient {
  private readonly baseUrl = 'https://api.example.com/api/dfc';

  async authenticate(domain: string, password: string): Promise<TokenResponse> {
    const url = `${this.baseUrl}/auth/token`;
    // ... rest of implementation
  }

  async startBatch(): Promise<BatchResponse> {
    const url = `${this.baseUrl}/batch/start`;
    // ... rest of implementation
  }

  async uploadFile(batchId: string, file: Buffer): Promise<FileResponse> {
    const url = `${this.baseUrl}/upload/${batchId}`;
    // ... rest of implementation
  }
}
```

**After**:
```typescript
class DataForgeClient {
  private readonly baseUrl = 'https://api.example.com/api/v1/device';

  async authenticate(domain: string, password: string): Promise<TokenResponse> {
    const url = `${this.baseUrl}/auth/token`;
    // ... rest of implementation (unchanged)
  }

  async startBatch(): Promise<BatchResponse> {
    const url = `${this.baseUrl}/batches/start`;
    // ... rest of implementation (unchanged)
  }

  async uploadFile(batchId: string, file: Buffer): Promise<FileResponse> {
    const url = `${this.baseUrl}/files/batches/${batchId}/upload`;
    // ... rest of implementation (unchanged)
  }
}
```

---

## Security Changes

### Authentication Routing

**Before (Implicit)**:
- Requests to `/api/dfc/**` were implicitly routed to Custom JWT authentication
- No explicit security filter chain separation

**After (Explicit)**:
- Requests to `/api/v1/device/**` are **explicitly** routed to Custom JWT authentication
- Separate security filter chain with `@Order(1)` precedence
- Keycloak tokens are **explicitly rejected** with 403 Forbidden

### Token Validation

**Unchanged**:
- Custom JWT signature validation (HMAC-SHA256)
- Token expiration enforcement
- Embedded claims: `siteId`, `accountId`, `domain`
- Token lifetime: 30 days (configurable)

### Authorization

**Unchanged**:
- Batch operations verify `siteId` ownership
- File uploads verify batch ownership
- Error logging restricted to authenticated sites

---

## Error Handling

### Obsolete Endpoint Response

When clients attempt to use old endpoints, they receive:

```http
HTTP/1.1 410 Gone
Content-Type: application/json

{
  "timestamp": "2025-11-06T12:00:00Z",
  "status": 410,
  "error": "Gone",
  "message": "This endpoint has been removed. Please migrate to the new unified Device API structure.",
  "path": "/api/dfc/batch/start",
  "migration": {
    "oldPath": "/api/dfc/batch/start",
    "newPath": "/api/v1/device/batches/start",
    "documentation": "https://docs.example.com/api-device-migration"
  }
}
```

### Authentication Failures

**401 Unauthorized** (unchanged):
- Missing Authorization header
- Invalid JWT signature
- Expired JWT token
- Invalid Basic Auth credentials

**403 Forbidden** (new behavior):
- Keycloak token presented to Device API endpoint
- Custom JWT token presented to UI/Admin API endpoint

---

## Testing Recommendations

### Unit Tests

Update all unit tests that reference old endpoint paths:

```java
// Before
mockMvc.perform(post("/api/dfc/batch/start"))
    .andExpect(status().isOk());

// After
mockMvc.perform(post("/api/v1/device/batches/start"))
    .andExpect(status().isOk());
```

### Integration Tests

Run full integration test suite against new endpoints:

1. **Authentication Flow**: Generate token → Use token for operations
2. **Batch Lifecycle**: Start → Upload files → Complete
3. **Error Scenarios**: Network errors → Retry → Success
4. **Authorization**: Verify `siteId` enforcement

### Contract Tests

Update contract tests to verify:
- Request/response schemas unchanged
- HTTP status codes unchanged
- Error response formats unchanged

---

## Performance Expectations

### Response Time

**Expected**: No performance degradation (±5% tolerance)

| Operation          | Before Migration | After Migration | Change   |
|--------------------|------------------|-----------------|----------|
| Token Generation   | ~150ms           | ~150ms          | 0%       |
| Start Batch        | ~100ms           | ~100ms          | 0%       |
| File Upload (1MB)  | ~500ms           | ~500ms          | 0%       |
| Complete Batch     | ~80ms            | ~80ms           | 0%       |
| Log Error          | ~50ms            | ~50ms           | 0%       |

### Throughput

**Expected**: Identical throughput capacity

- Max concurrent batches per site: 5 (unchanged)
- Max file size per upload: 500MB (unchanged)
- Max files per batch: Unlimited (unchanged)

---

## Rollback Plan

If critical issues are discovered after migration:

### Emergency Rollback (NOT RECOMMENDED)

1. **Revert Backend**: Deploy previous commit (`ac2410a` - before Spec 010)
2. **Revert Clients**: Deploy client code with old endpoint paths
3. **Database**: No changes needed (no schema migration)

### Recommended Approach

Instead of rollback, **fix forward**:
1. Identify specific issue (authentication, routing, performance)
2. Apply targeted fix
3. Test in staging environment
4. Deploy fix to production

**Why**: Rollback requires coordinating backend + all client deployments simultaneously, which is high-risk.

---

## Support & Resources

### Documentation

- **API Specification**: [Spec 010 - API Unification Goal](../specs/010-api-unification-goal/spec.md)
- **OpenAPI Docs**: [Swagger UI](http://localhost:8080/swagger-ui.html) (local development)
- **Backend Constants**: [ApiRoutes.java](../src/main/java/com/bitbi/dfm/shared/api/ApiRoutes.java)

### Contact

- **Backend Team**: backend@example.com
- **DevOps Team**: devops@example.com
- **Issue Tracker**: [GitHub Issues](https://github.com/example/data-forge-middleware/issues)

### Migration Assistance

If you encounter issues during migration:

1. **Check Logs**: Review backend logs for authentication failures
2. **Verify Tokens**: Ensure Custom JWT tokens are used (not Keycloak)
3. **Test Endpoints**: Use Postman/curl to test individual endpoints
4. **Contact Support**: Reach out to backend team with error details

---

## Frequently Asked Questions

### Q: Do I need to update my JWT tokens?

**A:** No. JWT token generation logic is unchanged. Tokens generated via the new `/api/v1/device/auth/token` endpoint have identical structure and claims.

### Q: Will old tokens work with new endpoints?

**A:** Yes. Tokens generated before migration will work with new endpoints as long as they haven't expired (30-day lifetime).

### Q: Do I need to update request/response formats?

**A:** No. All request and response DTOs are unchanged. Only endpoint paths have changed.

### Q: What happens if I forget to update an endpoint?

**A:** Your client will receive a **410 Gone** response with guidance on the new endpoint path. Check logs for migration errors.

### Q: Can I use both old and new endpoints during migration?

**A:** No. Old endpoints are immediately obsolete and return **410 Gone**. Migration must be completed in a single deployment.

### Q: Is there a grace period for old endpoints?

**A:** No. This is a **coordinated migration** - all clients must update simultaneously. Old endpoints are removed immediately.

### Q: How do I test new endpoints before deploying?

**A:** Use a staging environment with the new backend deployed. Update your client code to use new endpoints and test thoroughly before production deployment.

### Q: Do file upload limits change?

**A:** No. File size limit (500MB per file) and batch constraints (5 concurrent per site) are unchanged.

### Q: Does this affect my S3 file paths?

**A:** No. S3 storage paths (`accountId/domain/date/time/filename`) are unchanged.

### Q: What about rate limiting?

**A:** Rate limiting (if configured) applies equally to new endpoint structure. No changes to rate limit policies.

---

## Conclusion

This migration is a **structural refactoring** with **zero functional changes**. All business logic, validation, authentication, and authorization remain identical. Only endpoint paths have changed to follow the unified `/api/v1/device/**` structure.

**Key Takeaways**:
- ✅ Update endpoint paths only (request/response formats unchanged)
- ✅ Use same Custom JWT authentication
- ✅ Test thoroughly in staging before production
- ✅ Coordinate deployment of backend + all clients simultaneously
- ✅ Monitor logs for authentication failures after migration

For additional assistance, contact the backend team or refer to the API Unification specification document.

---

**Document Version**: 1.0.0
**Last Updated**: 2025-11-06
**Maintained By**: Data Forge Backend Team

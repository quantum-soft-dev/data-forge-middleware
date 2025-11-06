# API Contracts: API Unification

**Feature**: API Unification
**Date**: 2025-11-05
**Status**: Complete

## Overview

This directory contains OpenAPI 3.0 contract specifications for the unified API structure. All contracts define endpoint paths, request/response schemas, and security requirements.

**Key Changes**:
- Device API endpoints moved to `/api/v1/device/*` namespace with Custom JWT authentication
- UI/Admin API endpoints moved to `/api/v1/*` namespace with Keycloak OAuth2 authentication
- Request and response schemas **unchanged** (100% backward compatible)

---

## Contract Files

### 1. `device-api-contract.yaml`
**Scope**: Device API endpoints for client devices (IoT, mobile, data collection)
**Authentication**: Custom JWT (`bearerAuth` security scheme)
**Endpoints**:
- `/api/v1/device/auth/token` - Token generation (Basic Auth → JWT)
- `/api/v1/device/batches/*` - Batch lifecycle management
- `/api/v1/device/files/*` - File upload operations
- `/api/v1/device/errors/*` - Error logging

### 2. `admin-api-contract.yaml`
**Scope**: UI/Admin API endpoints for web interface
**Authentication**: Keycloak OAuth2 (`oauth2` security scheme)
**Endpoints**:
- `/api/v1/accounts/*` - Account management (ROLE_ADMIN)
- `/api/v1/sites/*` - Site management (ROLE_ADMIN)
- `/api/v1/batches/*` - Batch administration (ROLE_ADMIN)
- `/api/v1/history/batches/*` - Upload history (authenticated users)
- `/api/v1/errors/*` - Error administration (ROLE_ADMIN)
- `/api/v1/comparisons/*` - File comparison operations (authenticated users)

### 3. `deprecated-endpoints.yaml`
**Scope**: Old endpoint paths that return 410 Gone
**Authentication**: N/A (rejected before authentication)
**Purpose**: Documents migration path from old to new endpoints

---

## Contract Validation

All contracts are validated against:
1. **OpenAPI 3.0 specification** - Valid YAML syntax and structure
2. **Functional requirements** - All FR-001 through FR-042 covered
3. **DTOs** - Request/response schemas match existing DTO structures
4. **Security schemes** - `bearerAuth` (Custom JWT) and `oauth2` (Keycloak) defined

---

## Usage

### During Development
1. Contract tests reference these specifications
2. MockMvc tests validate actual endpoints match contracts
3. SpringDoc OpenAPI generates runtime docs matching these contracts

### For Frontend Teams
1. Use `device-api-contract.yaml` for mobile/IoT client development
2. Use `admin-api-contract.yaml` for web UI development
3. Consult `deprecated-endpoints.yaml` for migration mapping

### For Testing
1. Contract tests import schemas from YAML files
2. Integration tests verify security requirements enforced
3. Performance tests validate response time constraints (SC-009: ±5%)

---

## Endpoint Migration Mapping

Comprehensive mapping available in `quickstart.md`. Quick reference:

| Old Endpoint | New Endpoint | API Type |
|--------------|--------------|----------|
| `POST /api/v1/auth/token` | `POST /api/v1/device/auth/token` | Device |
| `POST /api/dfc/batch/start` | `POST /api/v1/device/batches/start` | Device |
| `POST /api/dfc/batch/{batchId}/upload` | `POST /api/v1/device/files/batches/{batchId}/upload` | Device |
| `POST /api/dfc/error` | `POST /api/v1/device/errors` | Device |
| `GET /api/admin/accounts` | `GET /api/v1/accounts` | UI/Admin |
| `GET /api/admin/sites` | `GET /api/v1/sites` | UI/Admin |
| `GET /api/user/batches` | `GET /api/v1/history/batches` | UI/Admin |
| `GET /api/admin/errors` | `GET /api/v1/errors` | UI/Admin |
| `GET /api/v1/comparisons` | `GET /api/v1/comparisons` | UI/Admin (no change) |

Full mapping table with all 40+ endpoints available in [quickstart.md](../quickstart.md).

---

## Security Schemes

### bearerAuth (Custom JWT)
```yaml
securitySchemes:
  bearerAuth:
    type: http
    scheme: bearer
    bearerFormat: JWT
    description: Custom JWT token for device clients (site credentials)
```

**Usage**: All `/api/v1/device/**` endpoints

**Token Claims**:
- `siteId` - Authenticated site UUID
- `accountId` - Site's account UUID
- `domain` - Site domain
- `exp` - Token expiration (24 hours)

### oauth2 (Keycloak)
```yaml
securitySchemes:
  oauth2:
    type: oauth2
    flows:
      authorizationCode:
        authorizationUrl: https://keycloak.example.com/realms/dataforge/protocol/openid-connect/auth
        tokenUrl: https://keycloak.example.com/realms/dataforge/protocol/openid-connect/token
        scopes:
          openid: OpenID Connect authentication
          profile: User profile information
    description: Keycloak OAuth2 for web UI and admin operations
```

**Usage**: All `/api/v1/**` endpoints (excluding `/api/v1/device/**`)

**Token Claims**:
- `sub` - Keycloak user ID
- `preferred_username` - Username
- `realm_access.roles` - User roles (ROLE_ADMIN for admin endpoints)
- `accountId` - Custom claim for account filtering

---

## Response Schemas (Unchanged)

All response schemas remain identical to existing DTOs:

- `BatchResponseDto` - Batch details
- `FileUploadResponseDto` - File upload confirmation
- `ErrorLogResponseDto` - Error log details
- `AccountResponseDto` - Account information
- `SiteResponseDto` - Site information
- `ComparisonResponseDto` - Comparison metadata
- `ErrorResponseDto` - Standard error response (including 410 Gone)
- `PageResponseDto` - Paginated responses
- `CursorPageResponseDto` - Cursor-based pagination

**Validation**: Contract tests verify JSON schema compatibility with existing DTOs.

---

## Status

- ✅ Device API contract complete (`device-api-contract.yaml`)
- ✅ Admin API contract complete (`admin-api-contract.yaml`)
- ✅ Deprecated endpoints documented (`deprecated-endpoints.yaml`)
- ✅ Security schemes defined
- ✅ All functional requirements mapped to endpoints
- ✅ DTOs validated for backward compatibility

**Next**: Generate `quickstart.md` with migration guide and code examples.

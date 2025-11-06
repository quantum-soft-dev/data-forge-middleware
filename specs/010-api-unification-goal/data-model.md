# Data Model: API Unification

**Feature**: API Unification
**Date**: 2025-11-05
**Status**: Complete

## Overview

This feature is a **refactoring effort with zero database schema changes**. No new entities are created, no existing entities are modified. The data model remains unchanged - only the API presentation layer is restructured.

---

## Existing Entities (Unchanged)

This refactoring affects how these entities are accessed via API, but does not modify their structure:

### Account (Aggregate Root)
- **Purpose**: Represents a tenant/organization in the multi-tenant system
- **Key Attributes**: id, email, name, isActive, keycloakUserId, createdAt
- **Relationships**: One-to-Many with Site
- **Access Changes**:
  - **Old Path**: `/api/admin/accounts`
  - **New Path**: `/api/v1/accounts`
  - **Authentication**: Keycloak OAuth2 (unchanged)

### Site (Aggregate Root)
- **Purpose**: Represents a client authentication endpoint within an account
- **Key Attributes**: id, accountId, domain, displayName, clientSecret (hashed), isActive, createdAt
- **Relationships**: Many-to-One with Account, One-to-Many with Batch
- **Access Changes**:
  - **Old Path**: `/api/admin/sites`
  - **New Path**: `/api/v1/sites`
  - **Authentication**: Keycloak OAuth2 (unchanged)

### Batch (Aggregate Root)
- **Purpose**: Represents a file upload session
- **Key Attributes**: id, batchId (UUID), siteId, status, s3Path, startedAt, completedAt
- **Relationships**: Many-to-One with Site, One-to-Many with FileUpload, One-to-Many with ErrorLog
- **Access Changes**:
  - **Device API**:
    - **Old Path**: `/api/dfc/batch`
    - **New Path**: `/api/v1/device/batches`
    - **Authentication**: Custom JWT (unchanged)
  - **Admin API**:
    - **Old Path**: `/api/admin/batches`
    - **New Path**: `/api/v1/batches`
    - **Authentication**: Keycloak OAuth2 (unchanged)
  - **User History API**:
    - **Old Path**: `/api/user/batches`
    - **New Path**: `/api/v1/history/batches`
    - **Authentication**: Keycloak OAuth2 (unchanged)

### FileUpload
- **Purpose**: Represents an uploaded file within a batch
- **Key Attributes**: id, batchId, filename, s3Key, fileSize, checksum, uploadedAt
- **Relationships**: Many-to-One with Batch
- **Access Changes**:
  - **Device API**:
    - **Old Path**: `/api/dfc/batch/{batchId}/upload`
    - **New Path**: `/api/v1/device/files/batches/{batchId}/upload`
    - **Authentication**: Custom JWT (unchanged)
  - **User History API**:
    - **Old Path**: `/api/user/batches/{batchId}/files/{fileId}/download`
    - **New Path**: `/api/v1/history/batches/{batchId}/files/{fileId}/download`
    - **Authentication**: Keycloak OAuth2 (unchanged)

### ErrorLog
- **Purpose**: Represents an error event during batch processing
- **Key Attributes**: id, batchId (nullable), severity, message, source, metadata (JSONB), occurredAt
- **Relationships**: Many-to-One with Batch (optional)
- **Table Partitioning**: Partitioned by month on `occurred_at` column (unchanged)
- **Access Changes**:
  - **Device API**:
    - **Old Path**: `/api/dfc/error` and `/api/dfc/error/{batchId}`
    - **New Path**: `/api/v1/device/errors` and `/api/v1/device/errors/batches/{batchId}`
    - **Authentication**: Custom JWT (unchanged)
  - **Admin API**:
    - **Old Path**: `/api/admin/errors`
    - **New Path**: `/api/v1/errors`
    - **Authentication**: Keycloak OAuth2 (unchanged)

### FileComparison (Aggregate Root)
- **Purpose**: Represents a comparison between files in two upload sessions
- **Key Attributes**: id, accountId, currentBatchId, targetBatchId, status, statistics, createdAt
- **Relationships**: Many-to-One with Account, Many-to-One with Batch (current), Many-to-One with Batch (target), One-to-Many with ComparisonResult
- **Access Changes**: **NONE** - Already uses `/api/v1/comparisons` (compliant with new structure)

### ComparisonResult
- **Purpose**: Represents the diff result for a single file comparison
- **Key Attributes**: id, comparisonId, filename, changeType, unifiedDiff (JSONB), linesAdded, linesDeleted
- **Relationships**: Many-to-One with FileComparison
- **Access Changes**: **NONE** - Already uses `/api/v1/comparisons/{id}/results` (compliant)

### AdminActionLog
- **Purpose**: Audit log for administrative actions
- **Key Attributes**: id, action, targetAccountId, adminAccountId, success, ipAddress, userAgent, performedAt
- **Relationships**: Many-to-One with Account (target), Many-to-One with Account (admin)
- **Access Changes**:
  - **Old Path**: `/api/admin/accounts/{id}/audit-logs`
  - **New Path**: `/api/v1/accounts/{id}/audit-logs`
  - **Authentication**: Keycloak OAuth2 (unchanged)

---

## Database Schema

**Status**: ✅ **NO CHANGES REQUIRED**

All database tables, columns, indexes, constraints, and partitioning remain unchanged. This refactoring affects only:
- API endpoint paths
- Security filter routing
- OpenAPI documentation structure

**Flyway Migration**: ❌ **NOT NEEDED**

No `V0XX__api_unification.sql` migration file required.

---

## Value Objects (Unchanged)

These domain value objects are used in business logic but are not persisted as separate tables:

### JwtToken
- **Purpose**: Represents a Custom JWT token for device authentication
- **Attributes**: token (String), expiresAt (Instant), siteId (UUID), accountId (UUID)
- **Usage**: Device API authentication (`/api/v1/device/auth/token`)

### SiteCredentials
- **Purpose**: Represents site authentication credentials
- **Attributes**: domain (String), clientSecret (String - plaintext for validation)
- **Usage**: Device API token generation endpoint

### BatchStatus (Enum)
- **Values**: IN_PROGRESS, COMPLETED, FAILED, CANCELLED, EXPIRED
- **Usage**: Batch lifecycle management across all APIs

### ComparisonStatus (Enum)
- **Values**: PENDING, IN_PROGRESS, COMPLETED, FAILED
- **Usage**: File comparison workflows

### ChangeType (Enum)
- **Values**: ADDED, MODIFIED, UNCHANGED, REMOVED
- **Usage**: File comparison results

---

## DTOs (Request/Response) - Unchanged

All existing DTOs remain unchanged. No breaking changes to request or response structures:

### Authentication DTOs
- `TokenResponseDto` - JWT token response (Device API)
- `CreateAccountRequestDto` - Account creation (Admin API)
- `UpdateAccountRequestDto` - Account update (Admin API)

### Batch DTOs
- `BatchResponseDto` - Batch details
- `StartBatchRequestDto` - Start batch request (Device API)
- `BatchSummaryDto` - Upload history list item
- `BatchDetailDto` - Upload history detail

### File DTOs
- `FileUploadResponseDto` - File upload confirmation
- `FileMetadataDto` - File information
- `FileDownloadResponseDto` - Presigned URL response

### Error DTOs
- `ErrorLogResponseDto` - Error log details
- `LogErrorRequestDto` - Error logging request (Device API)
- `ErrorSummaryDto` - Error summary

### Comparison DTOs
- `ComparisonResponseDto` - Comparison metadata
- `ComparisonResultDto` - Individual file diff
- `ComparisonSummaryDto` - Summary statistics
- `CreateComparisonRequestDto` - Create comparison request

### Common DTOs
- `ErrorResponseDto` - Standard error response (used for 410 Gone responses)
- `PageResponseDto<T>` - Paginated list response
- `CursorPageResponseDto<T>` - Cursor-based pagination

---

## Security Context (Unchanged Semantics, Updated Routing)

### Custom JWT Claims (Device API)
- `siteId` - UUID of the authenticated site
- `accountId` - UUID of the site's account
- `domain` - Site domain
- **Path Change**: `/api/v1/auth/token` → `/api/v1/device/auth/token`
- **Routing Change**: Filter chain `@Order(1)` for `/api/v1/device/**`

### Keycloak JWT Claims (UI/Admin API)
- `sub` - Keycloak user ID
- `preferred_username` - Username
- `realm_access.roles` - User roles (including `ROLE_ADMIN`)
- `accountId` - Custom claim linking to Account entity
- **Path Change**: `/api/admin/*` → `/api/v1/*` (excluding `/api/v1/device/**`)
- **Routing Change**: Filter chain `@Order(2)` for `/api/v1/**`

---

## Summary

| Aspect | Status | Details |
|--------|--------|---------|
| **New Entities** | ❌ None | Refactoring only |
| **Modified Entities** | ❌ None | No schema changes |
| **New Value Objects** | ❌ None | Existing value objects reused |
| **Modified DTOs** | ❌ None | 100% backward compatible |
| **Database Migration** | ❌ Not Required | No Flyway migration needed |
| **API Contracts** | ⚠️ Updated | Endpoint paths changed, payloads unchanged |
| **Security Claims** | ✅ Unchanged | JWT structure identical |
| **Business Logic** | ✅ Unchanged | Services, repositories, domain logic untouched |

**Validation**:
- ✅ All existing contract tests pass with updated paths
- ✅ All DTOs remain JSON-compatible with old API
- ✅ Database queries unchanged (same repositories used)
- ✅ Business logic untouched (services delegate identically)

**Next**: Generate OpenAPI contracts in `/contracts` directory documenting new endpoint paths with unchanged request/response schemas.

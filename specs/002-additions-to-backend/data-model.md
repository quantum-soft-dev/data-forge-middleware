# Data Model: DTO Entities

**Feature**: Additions to BACKEND
**Date**: 2025-10-09

## Overview

This document defines the Data Transfer Object (DTO) entities for standardizing API responses across all controllers. All DTOs are implemented as Java records for immutability, with companion builder classes using Lombok @Builder for entity-to-DTO conversion.

## DTO Design Pattern

```java
public record BatchResponseDto(UUID id, UUID siteId, String status, ...) {

    public static BatchResponseDto fromEntity(Batch batch) {
        return new BatchResponseDto(
            batch.getId(),
            batch.getSiteId(),
            batch.getStatus().name(),
            ...
        );
    }
}
```

**Key Principles:**
- Immutable records for thread safety
- Static `fromEntity()` methods for mapping
- No validation annotations (read-only responses)
- Flat structure (no nested DTOs for relationships, only IDs)
- Separate from domain entities (presentation layer concern)

## DTO Entities

### BatchResponseDto

**Purpose**: Response DTO for batch operations

**Fields:**

| Field | Type | Required | Nullable | Description |
|-------|------|----------|----------|-------------|
| id | UUID | ✓ | No | Unique batch identifier |
| batchId | UUID | ✓ | No | Alias for id (backward compatibility) |
| siteId | UUID | ✓ | No | Site that owns this batch |
| status | String | ✓ | No | Current status (ACTIVE, COMPLETED, FAILED, CANCELLED, EXPIRED) |
| s3Path | String | ✓ | No | S3 path for uploaded files |
| uploadedFilesCount | Integer | ✓ | No | Number of files uploaded |
| totalSize | Long | ✓ | No | Total size in bytes |
| hasErrors | Boolean | ✓ | No | Whether batch has error logs |
| startedAt | Instant | ✓ | No | Batch start timestamp |
| completedAt | Instant | No | Yes | Batch completion timestamp (null if active) |

**Mapping:** `BatchResponseDto.fromEntity(Batch)` → Copies all fields, converts `BatchStatus` enum to string

**Relationships:** None (flat structure)

**Location:** `src/main/java/com/bitbi/dfm/batch/presentation/dto/BatchResponseDto.java`

---

### ErrorLogResponseDto

**Purpose**: Response DTO for error log entries

**Fields:**

| Field | Type | Required | Nullable | Description |
|-------|------|----------|----------|-------------|
| id | UUID | ✓ | No | Unique error log identifier |
| batchId | UUID | ✓ | No | Batch this error belongs to |
| severity | String | ✓ | No | Severity level (INFO, WARN, ERROR, FATAL) |
| message | String | ✓ | No | Error message |
| source | String | ✓ | No | Error source (e.g., "file_upload", "validation") |
| metadata | Map<String, Object> | No | Yes | Additional JSONB metadata |
| occurredAt | Instant | ✓ | No | Error occurrence timestamp |

**Mapping:** `ErrorLogResponseDto.fromEntity(ErrorLog)` → Includes JSONB metadata as Map

**Relationships:** References batchId (no nested object)

**Location:** `src/main/java/com/bitbi/dfm/error/presentation/dto/ErrorLogResponseDto.java`

---

### FileUploadResponseDto

**Purpose**: Response DTO for uploaded files

**Fields:**

| Field | Type | Required | Nullable | Description |
|-------|------|----------|----------|-------------|
| id | UUID | ✓ | No | Unique file upload identifier |
| batchId | UUID | ✓ | No | Batch this file belongs to |
| filename | String | ✓ | No | Original filename |
| s3Key | String | ✓ | No | S3 object key |
| fileSize | Long | ✓ | No | File size in bytes |
| checksum | String | ✓ | No | MD5 checksum |
| uploadedAt | Instant | ✓ | No | Upload timestamp |

**Mapping:** `FileUploadResponseDto.fromEntity(FileUpload)`

**Relationships:** References batchId

**Location:** `src/main/java/com/bitbi/dfm/upload/presentation/dto/FileUploadResponseDto.java`

---

### AccountResponseDto

**Purpose**: Response DTO for account data (admin endpoints)

**Fields:**

| Field | Type | Required | Nullable | Description |
|-------|------|----------|----------|-------------|
| id | UUID | ✓ | No | Unique account identifier |
| email | String | ✓ | No | Account email address |
| name | String | ✓ | No | Account name |
| isActive | Boolean | ✓ | No | Active status |
| createdAt | Instant | ✓ | No | Creation timestamp |
| maxConcurrentBatches | Integer | ✓ | No | Max concurrent batches limit |

**Mapping:** `AccountResponseDto.fromEntity(Account)` → Excludes sensitive fields (passwords, internal metadata)

**Relationships:** None (admin endpoints return flat structure)

**Location:** `src/main/java/com/bitbi/dfm/account/presentation/dto/AccountResponseDto.java`

---

### SiteResponseDto

**Purpose**: Response DTO for site data

**Fields:**

| Field | Type | Required | Nullable | Description |
|-------|------|----------|----------|-------------|
| id | UUID | ✓ | No | Unique site identifier |
| accountId | UUID | ✓ | No | Account this site belongs to |
| domain | String | ✓ | No | Site domain name |
| name | String | ✓ | No | Site display name |
| isActive | Boolean | ✓ | No | Active status |
| createdAt | Instant | ✓ | No | Creation timestamp |

**Mapping:** `SiteResponseDto.fromEntity(Site)` → Excludes `clientSecret` for security

**Relationships:** References accountId

**Location:** `src/main/java/com/bitbi/dfm/site/presentation/dto/SiteResponseDto.java`

---

### TokenResponseDto

**Purpose**: Response DTO for JWT token generation

**Fields:**

| Field | Type | Required | Nullable | Description |
|-------|------|----------|----------|-------------|
| token | String | ✓ | No | JWT token string |
| expiresAt | Instant | ✓ | No | Token expiration timestamp |
| siteId | UUID | ✓ | No | Site ID from token claims |
| domain | String | ✓ | No | Domain from token claims |

**Mapping:** `TokenResponseDto.fromToken(JwtToken)` → Extracts claims from custom JwtToken value object

**Relationships:** None

**Location:** `src/main/java/com/bitbi/dfm/auth/presentation/dto/TokenResponseDto.java`

---

### ErrorResponseDto (Shared)

**Purpose**: Standardized error response DTO for all endpoints

**Fields:**

| Field | Type | Required | Nullable | Description |
|-------|------|----------|----------|-------------|
| timestamp | Instant | ✓ | No | Error occurrence timestamp |
| status | Integer | ✓ | No | HTTP status code |
| error | String | ✓ | No | HTTP status reason phrase |
| message | String | ✓ | No | Error message (generic for auth failures per FR-014) |
| path | String | ✓ | No | Request path that caused error |

**Mapping:** Constructed from exception and `HttpServletRequest` context in `GlobalExceptionHandler`

**Relationships:** None

**Location:** `src/main/java/com/bitbi/dfm/shared/presentation/dto/ErrorResponseDto.java`

**Usage:** All controller exception responses, authentication failures, validation errors

---

### PageResponseDto<T> (Shared Generic)

**Purpose**: Generic wrapper for paginated responses

**Fields:**

| Field | Type | Required | Nullable | Description |
|-------|------|----------|----------|-------------|
| content | List<T> | ✓ | No | List of DTOs for current page |
| page | Integer | ✓ | No | Current page number (0-indexed) |
| size | Integer | ✓ | No | Page size |
| totalElements | Long | ✓ | No | Total number of elements |
| totalPages | Integer | ✓ | No | Total number of pages |

**Mapping:** `PageResponseDto.of(Page<Entity>, Function<Entity, T>)` → Maps Spring Data `Page` with custom entity-to-DTO converter

**Relationships:** Generic container

**Location:** `src/main/java/com/bitbi/dfm/shared/presentation/dto/PageResponseDto.java`

**Usage:** All paginated list endpoints (admin account/site listings, error log queries)

---

## DTO-Entity Mapping Guidelines

1. **Immutability**: All DTOs are immutable records
2. **Static Factories**: Use static `fromEntity()` methods, not constructors
3. **Enum Conversion**: Convert enums to strings (e.g., `BatchStatus.ACTIVE` → `"ACTIVE"`)
4. **Timestamp Format**: Use `Instant` type (Jackson serializes to ISO-8601 automatically)
5. **Null Handling**: Only nullable fields should be explicitly nullable (use `@Nullable` annotation for clarity)
6. **Security**: Exclude sensitive fields (`clientSecret`, passwords, internal IDs)
7. **Relationships**: Use IDs only, no nested DTO objects (avoids circular references and N+1 queries)
8. **Validation**: None required on response DTOs (validation happens on request DTOs)

## Testing Strategy

Each DTO requires:
1. **Unit Test**: Verify `fromEntity()` mapping logic
2. **Contract Test**: Verify JSON serialization matches OpenAPI schema
3. **Integration Test**: Verify end-to-end controller → service → DTO flow

Example unit test:
```java
@Test
void fromEntity_shouldMapAllFields() {
    // Given
    Batch batch = createMockBatch();

    // When
    BatchResponseDto dto = BatchResponseDto.fromEntity(batch);

    // Then
    assertThat(dto.id()).isEqualTo(batch.getId());
    assertThat(dto.siteId()).isEqualTo(batch.getSiteId());
    assertThat(dto.status()).isEqualTo(batch.getStatus().name());
    // ... assert all fields
}
```

## Migration Notes

**Backward Compatibility:**
- All field names match existing `Map<String, Object>` keys
- Field types match existing Jackson serialization
- Response data content is identical (FR-003)

**Breaking Changes:**
- Response structure is now typed (clients using dynamic typing may need updates)
- Authentication rules changed (immediate breaking change per FR-011)

**Deployment:**
- Coordinate with all API consumers before deployment
- No database changes required
- Update OpenAPI documentation in Swagger UI

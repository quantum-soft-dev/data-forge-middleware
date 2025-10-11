# Data Model: DTO Definitions

**Feature**: Admin Controllers DTO Refactoring
**Date**: 2025-10-11
**Status**: Complete

This document defines all Data Transfer Object (DTO) records for admin API controllers.

## Overview

DTOs serve as the API contract boundary between HTTP endpoints and domain services. They are:
- **Immutable**: Java records (cannot be modified after creation)
- **Type-safe**: Compile-time validation of field types
- **Self-documenting**: Field names and types document the API
- **Validated**: Request DTOs use Jakarta Validation annotations
- **Serializable**: Automatically serialize to/from JSON via Jackson

## Request DTOs (User Input)

Request DTOs represent data sent by API clients to the server.

### 1. CreateAccountRequestDto

**Purpose**: Request body for creating a new account

**Location**: `src/main/java/com/bitbi/dfm/account/presentation/dto/CreateAccountRequestDto.java`

**Definition**:
```java
package com.bitbi.dfm.account.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for creating a new account.
 *
 * @param email Account email address (unique, validated format)
 * @param name Account display name (2-100 characters)
 */
public record CreateAccountRequestDto(
    @Schema(description = "Account email address", example = "admin@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid format")
    String email,

    @Schema(description = "Account display name", example = "John Doe", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100, message = "Name must be 2-100 characters")
    String name
) {}
```

**Validation Rules**:
- `email`: Required, non-blank, valid email format
- `name`: Required, non-blank, 2-100 characters

**Usage**: `POST /api/admin/accounts`

---

### 2. UpdateAccountRequestDto

**Purpose**: Request body for updating an existing account

**Location**: `src/main/java/com/bitbi/dfm/account/presentation/dto/UpdateAccountRequestDto.java`

**Definition**:
```java
package com.bitbi.dfm.account.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for updating an account.
 *
 * @param name New account display name (2-100 characters)
 */
public record UpdateAccountRequestDto(
    @Schema(description = "New account display name", example = "Jane Smith", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100, message = "Name must be 2-100 characters")
    String name
) {}
```

**Validation Rules**:
- `name`: Required, non-blank, 2-100 characters

**Usage**: `PUT /api/admin/accounts/{id}`

---

### 3. CreateSiteRequestDto

**Purpose**: Request body for creating a new site under an account

**Location**: `src/main/java/com/bitbi/dfm/site/presentation/dto/CreateSiteRequestDto.java`

**Definition**:
```java
package com.bitbi.dfm.site.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for creating a new site.
 *
 * @param domain Site domain (unique within account, lowercase)
 * @param displayName Site display name (2-100 characters)
 */
public record CreateSiteRequestDto(
    @Schema(description = "Site domain (unique within account)", example = "example.com", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Domain is required")
    @Pattern(regexp = "^[a-z0-9.-]+$", message = "Domain must contain only lowercase letters, numbers, dots, and hyphens")
    @Size(min = 3, max = 255, message = "Domain must be 3-255 characters")
    String domain,

    @Schema(description = "Site display name", example = "Example Website", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Display name is required")
    @Size(min = 2, max = 100, message = "Display name must be 2-100 characters")
    String displayName
) {}
```

**Validation Rules**:
- `domain`: Required, non-blank, 3-255 characters, lowercase alphanumeric with dots/hyphens
- `displayName`: Required, non-blank, 2-100 characters

**Usage**: `POST /api/admin/accounts/{accountId}/sites`

---

### 4. UpdateSiteRequestDto

**Purpose**: Request body for updating an existing site

**Location**: `src/main/java/com/bitbi/dfm/site/presentation/dto/UpdateSiteRequestDto.java`

**Definition**:
```java
package com.bitbi.dfm.site.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for updating a site.
 *
 * @param displayName New site display name (2-100 characters)
 */
public record UpdateSiteRequestDto(
    @Schema(description = "New site display name", example = "Updated Website Name", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Display name is required")
    @Size(min = 2, max = 100, message = "Display name must be 2-100 characters")
    String displayName
) {}
```

**Validation Rules**:
- `displayName`: Required, non-blank, 2-100 characters

**Usage**: `PUT /api/admin/sites/{id}`

---

### 5. LogErrorRequestDto

**Purpose**: Request body for logging errors (standalone or batch-associated)

**Location**: `src/main/java/com/bitbi/dfm/error/presentation/dto/LogErrorRequestDto.java`

**Definition**:
```java
package com.bitbi.dfm.error.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Request DTO for logging an error.
 *
 * @param type Error type/category (e.g., "UPLOAD_FAILED", "VALIDATION_ERROR")
 * @param message Error message description
 * @param metadata Optional additional error context (JSON object)
 */
public record LogErrorRequestDto(
    @Schema(description = "Error type/category", example = "UPLOAD_FAILED", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Error type is required")
    @Size(max = 100, message = "Error type must not exceed 100 characters")
    String type,

    @Schema(description = "Error message description", example = "File upload failed: connection timeout", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Error message is required")
    @Size(max = 1000, message = "Error message must not exceed 1000 characters")
    String message,

    @Schema(description = "Optional additional error context", example = "{\"fileName\":\"data.csv\",\"fileSize\":1024}", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    Map<String, Object> metadata
) {}
```

**Validation Rules**:
- `type`: Required, non-blank, max 100 characters
- `message`: Required, non-blank, max 1000 characters
- `metadata`: Optional, arbitrary JSON object

**Usage**: `POST /api/dfc/error` (standalone), `POST /api/dfc/error/{batchId}` (batch-associated)

---

## Response DTOs (System Output)

Response DTOs represent data returned by the server to API clients.

### 6. AccountWithStatsResponseDto

**Purpose**: Response for account details including statistics

**Location**: `src/main/java/com/bitbi/dfm/account/presentation/dto/AccountWithStatsResponseDto.java`

**Definition**:
```java
package com.bitbi.dfm.account.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for account details with statistics.
 * Extends basic account information with aggregated metrics.
 *
 * @param id Account unique identifier
 * @param email Account email address
 * @param name Account display name
 * @param isActive Whether account is active
 * @param createdAt Account creation timestamp
 * @param sitesCount Total number of sites under this account
 * @param totalBatches Total number of batches across all sites
 * @param totalUploadedFiles Total number of uploaded files across all batches
 */
public record AccountWithStatsResponseDto(
    @Schema(description = "Account unique identifier", example = "123e4567-e89b-12d3-a456-426614174000")
    UUID id,

    @Schema(description = "Account email address", example = "admin@example.com")
    String email,

    @Schema(description = "Account display name", example = "John Doe")
    String name,

    @Schema(description = "Whether account is active", example = "true")
    Boolean isActive,

    @Schema(description = "Account creation timestamp", example = "2025-01-15T10:30:00Z")
    Instant createdAt,

    @Schema(description = "Total number of sites", example = "5")
    Integer sitesCount,

    @Schema(description = "Total number of batches", example = "120")
    Integer totalBatches,

    @Schema(description = "Total number of uploaded files", example = "1500")
    Integer totalUploadedFiles
) {}
```

**Factory Method**:
```java
public static AccountWithStatsResponseDto fromEntityAndStats(Account account, Map<String, Object> stats) {
    return new AccountWithStatsResponseDto(
        account.getId(),
        account.getEmail(),
        account.getName(),
        account.getIsActive(),
        account.getCreatedAt(),
        (Integer) stats.get("totalSites"),
        (Integer) stats.get("totalBatches"),
        (Integer) stats.get("totalFiles")
    );
}
```

**Usage**: `GET /api/admin/accounts/{id}`

---

### 7. AccountStatisticsDto

**Purpose**: Response for account statistics endpoint

**Location**: `src/main/java/com/bitbi/dfm/account/presentation/dto/AccountStatisticsDto.java`

**Definition**:
```java
package com.bitbi.dfm.account.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Response DTO for account statistics (admin endpoint).
 *
 * @param accountId Account unique identifier
 * @param sitesCount Total number of sites
 * @param activeSites Number of active sites
 * @param totalBatches Total number of batches
 * @param completedBatches Number of completed batches
 * @param failedBatches Number of failed batches
 * @param totalFiles Total number of uploaded files
 * @param totalStorageSize Total storage size in bytes
 */
public record AccountStatisticsDto(
    @Schema(description = "Account unique identifier", example = "123e4567-e89b-12d3-a456-426614174000")
    UUID accountId,

    @Schema(description = "Total number of sites", example = "5")
    Integer sitesCount,

    @Schema(description = "Number of active sites", example = "4")
    Integer activeSites,

    @Schema(description = "Total number of batches", example = "120")
    Integer totalBatches,

    @Schema(description = "Number of completed batches", example = "110")
    Integer completedBatches,

    @Schema(description = "Number of failed batches", example = "5")
    Integer failedBatches,

    @Schema(description = "Total number of uploaded files", example = "1500")
    Integer totalFiles,

    @Schema(description = "Total storage size in bytes", example = "52428800")
    Long totalStorageSize
) {}
```

**Factory Method**:
```java
public static AccountStatisticsDto fromStatsMap(Map<String, Object> stats) {
    return new AccountStatisticsDto(
        (UUID) stats.get("accountId"),
        (Integer) stats.getOrDefault("sitesCount", 0),
        (Integer) stats.getOrDefault("activeSites", 0),
        (Integer) stats.getOrDefault("totalBatches", 0),
        (Integer) stats.getOrDefault("completedBatches", 0),
        (Integer) stats.getOrDefault("failedBatches", 0),
        (Integer) stats.getOrDefault("totalFiles", 0),
        (Long) stats.getOrDefault("totalStorageSize", 0L)
    );
}
```

**Usage**: `GET /api/admin/accounts/{id}/stats`, `GET /api/admin/accounts/{id}/statistics`

---

### 8. SiteCreationResponseDto

**Purpose**: Response for site creation (includes plaintext client secret, one-time only)

**Location**: `src/main/java/com/bitbi/dfm/site/presentation/dto/SiteCreationResponseDto.java`

**Definition**:
```java
package com.bitbi.dfm.site.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for site creation.
 * Includes plaintext client secret (only shown at creation time).
 *
 * @param id Site unique identifier
 * @param accountId Parent account identifier
 * @param domain Site domain
 * @param name Site display name
 * @param isActive Whether site is active
 * @param createdAt Site creation timestamp
 * @param clientSecret Plaintext client secret (NEVER SHOWN AGAIN)
 */
public record SiteCreationResponseDto(
    @Schema(description = "Site unique identifier", example = "123e4567-e89b-12d3-a456-426614174000")
    UUID id,

    @Schema(description = "Parent account identifier", example = "987fcdeb-51a2-43f7-9c3d-123456789abc")
    UUID accountId,

    @Schema(description = "Site domain", example = "example.com")
    String domain,

    @Schema(description = "Site display name", example = "Example Website")
    String name,

    @Schema(description = "Whether site is active", example = "true")
    Boolean isActive,

    @Schema(description = "Site creation timestamp", example = "2025-01-15T10:30:00Z")
    Instant createdAt,

    @Schema(description = "Plaintext client secret (only shown at creation)", example = "secret_abc123xyz")
    String clientSecret
) {}
```

**Factory Method**:
```java
public static SiteCreationResponseDto fromCreationResult(Site site, String plaintextSecret) {
    return new SiteCreationResponseDto(
        site.getId(),
        site.getAccountId(),
        site.getDomain(),
        site.getName(),
        site.getIsActive(),
        site.getCreatedAt(),
        plaintextSecret
    );
}
```

**Usage**: `POST /api/admin/accounts/{accountId}/sites`

---

### 9. SiteStatisticsDto

**Purpose**: Response for site statistics endpoint

**Location**: `src/main/java/com/bitbi/dfm/site/presentation/dto/SiteStatisticsDto.java`

**Definition**:
```java
package com.bitbi.dfm.site.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Response DTO for site statistics.
 *
 * @param siteId Site unique identifier
 * @param totalBatches Total number of batches for this site
 * @param completedBatches Number of completed batches
 * @param failedBatches Number of failed batches
 * @param totalFiles Total number of uploaded files
 * @param totalStorageSize Total storage size in bytes
 */
public record SiteStatisticsDto(
    @Schema(description = "Site unique identifier", example = "123e4567-e89b-12d3-a456-426614174000")
    UUID siteId,

    @Schema(description = "Total number of batches", example = "50")
    Integer totalBatches,

    @Schema(description = "Number of completed batches", example = "45")
    Integer completedBatches,

    @Schema(description = "Number of failed batches", example = "2")
    Integer failedBatches,

    @Schema(description = "Total number of uploaded files", example = "600")
    Integer totalFiles,

    @Schema(description = "Total storage size in bytes", example = "20971520")
    Long totalStorageSize
) {}
```

**Factory Method**:
```java
public static SiteStatisticsDto fromStatsMap(Map<String, Object> stats) {
    return new SiteStatisticsDto(
        (UUID) stats.get("siteId"),
        (Integer) stats.getOrDefault("totalBatches", 0),
        (Integer) stats.getOrDefault("completedBatches", 0),
        (Integer) stats.getOrDefault("failedBatches", 0),
        (Integer) stats.getOrDefault("totalFiles", 0),
        (Long) stats.getOrDefault("totalStorageSize", 0L)
    );
}
```

**Usage**: `GET /api/admin/sites/{id}/statistics`

---

### 10. BatchSummaryDto

**Purpose**: Response for batch list items (summary without files)

**Location**: `src/main/java/com/bitbi/dfm/batch/presentation/dto/BatchSummaryDto.java`

**Definition**:
```java
package com.bitbi.dfm.batch.presentation.dto;

import com.bitbi.dfm.batch.domain.BatchStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for batch summary (list view).
 *
 * @param id Batch unique identifier
 * @param siteId Parent site identifier
 * @param status Batch status (ACTIVE, IN_PROGRESS, COMPLETED, EXPIRED)
 * @param s3Path S3 path prefix for batch files
 * @param uploadedFilesCount Number of files uploaded
 * @param totalSize Total size of all files in bytes
 * @param hasErrors Whether batch has any error logs
 * @param startedAt Batch start timestamp
 * @param completedAt Batch completion timestamp (null if not completed)
 * @param createdAt Batch creation timestamp
 */
public record BatchSummaryDto(
    @Schema(description = "Batch unique identifier", example = "123e4567-e89b-12d3-a456-426614174000")
    UUID id,

    @Schema(description = "Parent site identifier", example = "987fcdeb-51a2-43f7-9c3d-123456789abc")
    UUID siteId,

    @Schema(description = "Batch status", example = "COMPLETED")
    BatchStatus status,

    @Schema(description = "S3 path prefix for batch files", example = "account123/example.com/2025-01-15/batch_abc")
    String s3Path,

    @Schema(description = "Number of files uploaded", example = "25")
    Integer uploadedFilesCount,

    @Schema(description = "Total size of all files in bytes", example = "10485760")
    Long totalSize,

    @Schema(description = "Whether batch has any error logs", example = "false")
    Boolean hasErrors,

    @Schema(description = "Batch start timestamp", example = "2025-01-15T10:30:00Z")
    Instant startedAt,

    @Schema(description = "Batch completion timestamp", example = "2025-01-15T11:45:00Z")
    Instant completedAt,

    @Schema(description = "Batch creation timestamp", example = "2025-01-15T10:30:00Z")
    Instant createdAt
) {}
```

**Factory Method**:
```java
public static BatchSummaryDto fromEntity(Batch batch) {
    return new BatchSummaryDto(
        batch.getId(),
        batch.getSiteId(),
        batch.getStatus(),
        batch.getS3Path(),
        batch.getUploadedFilesCount(),
        batch.getTotalSize(),
        batch.getHasErrors(),
        batch.getStartedAt(),
        batch.getCompletedAt(),
        batch.getCreatedAt()
    );
}
```

**Usage**: `GET /api/admin/batches` (paginated list)

---

### 11. BatchDetailResponseDto

**Purpose**: Response for batch details including files and site domain

**Location**: `src/main/java/com/bitbi/dfm/batch/presentation/dto/BatchDetailResponseDto.java`

**Definition**:
```java
package com.bitbi.dfm.batch.presentation.dto;

import com.bitbi.dfm.batch.domain.BatchStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO for batch details (detail view with files).
 *
 * @param id Batch unique identifier
 * @param siteId Parent site identifier
 * @param siteDomain Site domain name (for display purposes)
 * @param status Batch status
 * @param s3Path S3 path prefix for batch files
 * @param uploadedFilesCount Number of files uploaded
 * @param totalSize Total size of all files in bytes
 * @param hasErrors Whether batch has any error logs
 * @param startedAt Batch start timestamp
 * @param completedAt Batch completion timestamp (null if not completed)
 * @param createdAt Batch creation timestamp
 * @param files List of uploaded files in this batch
 */
public record BatchDetailResponseDto(
    @Schema(description = "Batch unique identifier", example = "123e4567-e89b-12d3-a456-426614174000")
    UUID id,

    @Schema(description = "Parent site identifier", example = "987fcdeb-51a2-43f7-9c3d-123456789abc")
    UUID siteId,

    @Schema(description = "Site domain name", example = "example.com")
    String siteDomain,

    @Schema(description = "Batch status", example = "COMPLETED")
    BatchStatus status,

    @Schema(description = "S3 path prefix for batch files", example = "account123/example.com/2025-01-15/batch_abc")
    String s3Path,

    @Schema(description = "Number of files uploaded", example = "25")
    Integer uploadedFilesCount,

    @Schema(description = "Total size of all files in bytes", example = "10485760")
    Long totalSize,

    @Schema(description = "Whether batch has any error logs", example = "false")
    Boolean hasErrors,

    @Schema(description = "Batch start timestamp", example = "2025-01-15T10:30:00Z")
    Instant startedAt,

    @Schema(description = "Batch completion timestamp", example = "2025-01-15T11:45:00Z")
    Instant completedAt,

    @Schema(description = "Batch creation timestamp", example = "2025-01-15T10:30:00Z")
    Instant createdAt,

    @Schema(description = "List of uploaded files in this batch")
    List<UploadedFileDto> files
) {}
```

**Factory Method**:
```java
public static BatchDetailResponseDto fromBatchAndFiles(Batch batch, String siteDomain, List<UploadedFileDto> files) {
    return new BatchDetailResponseDto(
        batch.getId(),
        batch.getSiteId(),
        siteDomain,
        batch.getStatus(),
        batch.getS3Path(),
        batch.getUploadedFilesCount(),
        batch.getTotalSize(),
        batch.getHasErrors(),
        batch.getStartedAt(),
        batch.getCompletedAt(),
        batch.getCreatedAt(),
        files
    );
}
```

**Usage**: `GET /api/admin/batches/{id}`

---

### 12. UploadedFileDto

**Purpose**: Response for uploaded file information within batch details

**Location**: `src/main/java/com/bitbi/dfm/batch/presentation/dto/UploadedFileDto.java`

**Definition**:
```java
package com.bitbi.dfm.batch.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for uploaded file information.
 * Nested within BatchDetailResponseDto.
 *
 * @param id File unique identifier
 * @param originalFileName Original filename as uploaded by client
 * @param s3Key S3 object key (full path)
 * @param fileSize File size in bytes
 * @param contentType MIME content type
 * @param checksum MD5 checksum
 * @param uploadedAt File upload timestamp
 */
public record UploadedFileDto(
    @Schema(description = "File unique identifier", example = "123e4567-e89b-12d3-a456-426614174000")
    UUID id,

    @Schema(description = "Original filename", example = "data.csv")
    String originalFileName,

    @Schema(description = "S3 object key", example = "account123/example.com/2025-01-15/batch_abc/data.csv")
    String s3Key,

    @Schema(description = "File size in bytes", example = "1048576")
    Long fileSize,

    @Schema(description = "MIME content type", example = "text/csv")
    String contentType,

    @Schema(description = "MD5 checksum", example = "d41d8cd98f00b204e9800998ecf8427e")
    String checksum,

    @Schema(description = "File upload timestamp", example = "2025-01-15T10:35:00Z")
    Instant uploadedAt
) {}
```

**Factory Method**:
```java
public static UploadedFileDto fromEntity(UploadedFile file) {
    return new UploadedFileDto(
        file.getId(),
        file.getOriginalFileName(),
        file.getS3Key(),
        file.getFileSize(),
        file.getContentType(),
        file.getChecksum(),
        file.getUploadedAt()
    );
}
```

**Usage**: Nested in `BatchDetailResponseDto`

---

### 13. ErrorLogSummaryDto

**Purpose**: Response for error log list items

**Location**: `src/main/java/com/bitbi/dfm/error/presentation/dto/ErrorLogSummaryDto.java`

**Definition**:
```java
package com.bitbi.dfm.error.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for error log summary (list view).
 *
 * @param id Error log unique identifier
 * @param batchId Associated batch identifier (null for standalone errors)
 * @param siteId Site that logged the error
 * @param type Error type/category
 * @param message Error message description
 * @param metadata Additional error context (JSON object)
 * @param occurredAt Error occurrence timestamp
 */
public record ErrorLogSummaryDto(
    @Schema(description = "Error log unique identifier", example = "123e4567-e89b-12d3-a456-426614174000")
    UUID id,

    @Schema(description = "Associated batch identifier (null for standalone errors)", example = "987fcdeb-51a2-43f7-9c3d-123456789abc")
    UUID batchId,

    @Schema(description = "Site that logged the error", example = "abc12345-6789-0def-1234-56789abcdef0")
    UUID siteId,

    @Schema(description = "Error type/category", example = "UPLOAD_FAILED")
    String type,

    @Schema(description = "Error message description", example = "File upload failed: connection timeout")
    String message,

    @Schema(description = "Additional error context", example = "{\"fileName\":\"data.csv\",\"fileSize\":1024}")
    Map<String, Object> metadata,

    @Schema(description = "Error occurrence timestamp", example = "2025-01-15T10:30:00")
    LocalDateTime occurredAt
) {}
```

**Factory Method**:
```java
public static ErrorLogSummaryDto fromEntity(ErrorLog errorLog) {
    return new ErrorLogSummaryDto(
        errorLog.getId(),
        errorLog.getBatchId(),
        errorLog.getSiteId(),
        errorLog.getType(),
        errorLog.getMessage(),
        errorLog.getMetadata(),
        errorLog.getOccurredAt()
    );
}
```

**Usage**: `GET /api/admin/errors` (paginated list)

---

## DTO Relationships

```
Request DTOs (User → Server):
  CreateAccountRequestDto ──> AccountService.createAccount()
  UpdateAccountRequestDto ──> AccountService.updateAccount()
  CreateSiteRequestDto ────> SiteService.createSite()
  UpdateSiteRequestDto ────> SiteService.updateSite()
  LogErrorRequestDto ──────> ErrorLoggingService.logError()

Response DTOs (Server → User):
  AccountWithStatsResponseDto ←─ Account + Statistics (GET /accounts/{id})
  AccountStatisticsDto ←──────── Statistics Service (GET /accounts/{id}/stats)
  SiteCreationResponseDto ←───── Site + Plaintext Secret (POST /sites)
  SiteStatisticsDto ←─────────── Statistics Service (GET /sites/{id}/statistics)
  BatchSummaryDto ←──────────── Batch (GET /batches, list view)
  BatchDetailResponseDto ←────── Batch + Site + Files (GET /batches/{id})
    └── UploadedFileDto ←─────── UploadedFile (nested in BatchDetailResponseDto)
  ErrorLogSummaryDto ←────────── ErrorLog (GET /errors, list view)
```

## Validation Summary

| DTO | Required Fields | Constraints |
|-----|----------------|-------------|
| CreateAccountRequestDto | email, name | email format, name 2-100 chars |
| UpdateAccountRequestDto | name | name 2-100 chars |
| CreateSiteRequestDto | domain, displayName | domain 3-255 chars lowercase, displayName 2-100 chars |
| UpdateSiteRequestDto | displayName | displayName 2-100 chars |
| LogErrorRequestDto | type, message | type max 100 chars, message max 1000 chars |
| *Response DTOs* | N/A | No validation (system-generated data) |

## OpenAPI Documentation

All DTOs include `@Schema` annotations for OpenAPI documentation:
- Field descriptions
- Example values
- Required/optional indicators
- Format constraints (email, pattern, size)

These annotations automatically generate OpenAPI 3.0 schemas accessible via Swagger UI at `/swagger-ui.html`.

## Testing Strategy

1. **Unit Tests**: Validate DTO field constraints using `Validator` (Jakarta Validation)
2. **Contract Tests**: Verify JSON serialization/deserialization via MockMvc
3. **Integration Tests**: Validate end-to-end request/response flows with Testcontainers

---

**Implementation Order**: Follow user story priorities (P1: Request DTOs → P2: Response DTOs → P3: Exception handling → P4: OpenAPI documentation)

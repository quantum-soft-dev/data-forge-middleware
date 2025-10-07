# Admin API Contract Summary

**Base Path**: `/admin`
**Authentication**: Keycloak JWT with ADMIN role
**Authorization**: All endpoints require `ROLE_ADMIN`

---

## Account Management

### POST /admin/accounts
Create new account with email validation

**Request Body**:
```json
{
  "email": "user@example.com",
  "name": "John Doe"
}
```

**Response 201 Created**:
```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "email": "user@example.com",
  "name": "John Doe",
  "isActive": true,
  "createdAt": "2025-10-06T10:30:00Z"
}
```

---

### GET /admin/accounts
List all accounts with pagination

**Query Parameters**:
- `page` (integer, default: 0)
- `size` (integer, default: 20)
- `sort` (string, default: "createdAt,desc")

**Response 200 OK**:
```json
{
  "content": [...],
  "page": 0,
  "size": 20,
  "totalElements": 150,
  "totalPages": 8
}
```

---

### GET /admin/accounts/{id}
Get detailed account information with statistics

**Response 200 OK**:
```json
{
  "id": "a1b2c3d4-...",
  "email": "user@example.com",
  "name": "John Doe",
  "isActive": true,
  "createdAt": "2025-10-06T10:30:00Z",
  "updatedAt": "2025-10-06T11:00:00Z",
  "sitesCount": 3,
  "totalBatches": 120,
  "totalUploadedFiles": 4560
}
```

---

### PUT /admin/accounts/{id}
Update account details

**Request Body**:
```json
{
  "name": "John Smith",
  "isActive": true
}
```

---

### DELETE /admin/accounts/{id}
Soft delete account (sets isActive = false, cascades to sites)

**Response 204 No Content**

---

### GET /admin/accounts/{id}/stats
Get account statistics

**Response 200 OK**:
```json
{
  "accountId": "a1b2c3d4-...",
  "sitesCount": 3,
  "activeSites": 2,
  "totalBatches": 120,
  "completedBatches": 115,
  "failedBatches": 5,
  "totalFiles": 4560,
  "totalStorageSize": 15728640000,
  "lastUploadAt": "2025-10-06T10:30:00Z"
}
```

---

## Site Management

### POST /admin/accounts/{accountId}/sites
Create new site for account (auto-generates clientSecret)

**Request Body**:
```json
{
  "domain": "store-01.example.com",
  "displayName": "Store #1 - New York"
}
```

**Response 201 Created**:
```json
{
  "id": "b2c3d4e5-...",
  "accountId": "a1b2c3d4-...",
  "domain": "store-01.example.com",
  "displayName": "Store #1 - New York",
  "clientSecret": "c3d4e5f6-...",
  "isActive": true,
  "createdAt": "2025-10-06T10:30:00Z"
}
```

---

### GET /admin/accounts/{accountId}/sites
List sites for account

---

### DELETE /admin/sites/{id}
Soft delete site (sets isActive = false)

**Response 204 No Content**

---

## Batch Management

### GET /admin/batches
List batches with filtering

**Query Parameters**:
- `siteId` (UUID, optional)
- `status` (string, optional: IN_PROGRESS, COMPLETED, etc.)
- `page`, `size`, `sort`

**Response 200 OK**:
```json
{
  "content": [
    {
      "id": "c3d4e5f6-...",
      "siteId": "b2c3d4e5-...",
      "siteDomain": "store-01.example.com",
      "status": "COMPLETED",
      "uploadedFilesCount": 15,
      "totalSize": 45678900,
      "hasErrors": false,
      "startedAt": "2025-10-06T08:00:00Z",
      "completedAt": "2025-10-06T08:15:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 250,
  "totalPages": 13
}
```

---

### GET /admin/batches/{id}
Get batch details with file list

**Response 200 OK**:
```json
{
  "id": "c3d4e5f6-...",
  "siteId": "b2c3d4e5-...",
  "siteDomain": "store-01.example.com",
  "accountId": "a1b2c3d4-...",
  "status": "COMPLETED",
  "s3Path": "a1b2c3d4-.../store-01.example.com/2025-10-06/08-00/",
  "uploadedFilesCount": 15,
  "totalSize": 45678900,
  "hasErrors": false,
  "startedAt": "2025-10-06T08:00:00Z",
  "completedAt": "2025-10-06T08:15:00Z",
  "files": [
    {
      "id": "d4e5f6a7-...",
      "originalFileName": "sales.csv.gz",
      "fileSize": 3145728,
      "uploadedAt": "2025-10-06T08:05:00Z"
    }
  ]
}
```

---

### DELETE /admin/batches/{id}
Delete batch metadata (files remain in S3)

**Response 204 No Content**

---

## Error Log Management

### GET /admin/errors
List errors with filtering

**Query Parameters**:
- `siteId` (UUID, optional)
- `type` (string, optional)
- `startDate` (date YYYY-MM-DD, optional)
- `endDate` (date YYYY-MM-DD, optional)
- `page`, `size`

**Response 200 OK**:
```json
{
  "content": [
    {
      "id": "e5f6a7b8-...",
      "siteId": "b2c3d4e5-...",
      "siteDomain": "store-01.example.com",
      "batchId": "c3d4e5f6-...",
      "type": "FileReadError",
      "title": "Failed to read DBF file",
      "message": "File corrupted",
      "occurredAt": "2025-10-06T08:10:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 85,
  "totalPages": 5
}
```

---

### GET /admin/errors/export
Export error logs to CSV

**Query Parameters**: Same as GET /admin/errors

**Response 200 OK**:
```
Content-Type: text/csv
Content-Disposition: attachment; filename="error-logs-2025-10-06.csv"

id,siteId,siteDomain,batchId,type,title,message,occurredAt
e5f6a7b8-...,b2c3d4e5-...,store-01.example.com,c3d4e5f6-...,FileReadError,Failed to read DBF file,File corrupted,2025-10-06T08:10:00Z
```

---

## Contract Tests

All admin endpoints require contract tests validating:
1. Keycloak JWT authentication with ADMIN role
2. 403 Forbidden when role missing
3. Request/response schema compliance with OpenAPI spec
4. Pagination structure (page, size, totalElements, totalPages)
5. Sorting behavior (createdAt desc by default)
6. Standard error response format

Example test:
```java
@Test
void shouldListAccountsWhenAdminAuthenticated() {
    mockMvc.perform(get("/admin/accounts")
            .header("Authorization", "Bearer " + adminJwtToken)
            .param("page", "0")
            .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(20));
}

@Test
void shouldRejectAccessWhenNonAdminAuthenticated() {
    mockMvc.perform(get("/admin/accounts")
            .header("Authorization", "Bearer " + regularUserToken))
        .andExpect(status().isForbidden());
}
```

# Batch & Upload API Contract

**Base Path**: `/api/v1/batch`
**Authentication**: Bearer JWT (obtained from `/api/v1/auth/token`)

---

## POST /api/v1/batch/start

**Summary**: Create new batch for file uploads

**Request**:
- **Headers**:
  - `Authorization: Bearer {jwt_token}` (required)
- **Body**: None

**Responses**:

### 200 OK - Batch created
```json
{
  "batchId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

### 401 Unauthorized - Invalid/expired token
```json
{
  "timestamp": "2025-10-06T10:30:00Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Invalid or expired token",
  "path": "/api/v1/batch/start"
}
```

### 409 Conflict - Active batch already exists
```json
{
  "timestamp": "2025-10-06T10:30:00Z",
  "status": 409,
  "error": "Conflict",
  "message": "Active batch already exists for this site",
  "path": "/api/v1/batch/start"
}
```

**Business Rules**:
- Only one IN_PROGRESS batch per site allowed
- If previous IN_PROGRESS batch expired (timeout), automatically mark as NOT_COMPLETED and create new batch
- S3 directory created: `{accountId}/{domain}/{YYYY-MM-DD}/{HH-MM}/`
- Check account-level active batch limit (default 10)

---

## POST /api/v1/batch/{batchId}/upload

**Summary**: Upload one or more files to batch

**Request**:
- **Headers**:
  - `Authorization: Bearer {jwt_token}` (required)
  - `Content-Type: multipart/form-data`
- **Path Parameters**:
  - `batchId` (UUID, required): Target batch identifier
- **Body** (multipart/form-data):
  - `files` (array of File, required): One or more compressed CSV files (.gz)

**Responses**:

### 200 OK - Files uploaded
```json
{
  "status": "OK",
  "uploadedFiles": 2,
  "files": [
    {
      "fileName": "data1.csv.gz",
      "fileSize": 1048576,
      "uploadedAt": "2025-10-06T10:35:00Z"
    },
    {
      "fileName": "data2.csv.gz",
      "fileSize": 2097152,
      "uploadedAt": "2025-10-06T10:35:01Z"
    }
  ]
}
```

### 400 Bad Request - Duplicate filename or validation failure
```json
{
  "timestamp": "2025-10-06T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "File 'data1.csv.gz' already exists in this batch",
  "path": "/api/v1/batch/{batchId}/upload"
}
```

### 404 Not Found - Batch not found
```json
{
  "timestamp": "2025-10-06T10:30:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "Batch not found",
  "path": "/api/v1/batch/{batchId}/upload"
}
```

### 413 Payload Too Large - File size limit exceeded
```json
{
  "timestamp": "2025-10-06T10:30:00Z",
  "status": 413,
  "error": "Payload Too Large",
  "message": "File size exceeds maximum allowed size of 128MB",
  "path": "/api/v1/batch/{batchId}/upload"
}
```

**Business Rules**:
- Batch must be in IN_PROGRESS status
- Batch must belong to authenticated site
- Maximum file size: 128MB (configurable)
- Maximum files per batch: 1000 (configurable)
- Filename uniqueness per batch (only for successful uploads)
- Failed uploads: discard partial data, allow retry with same filename
- S3 retry logic: 3 attempts with fixed interval, then fail

---

## POST /api/v1/batch/{batchId}/complete

**Summary**: Mark batch as successfully completed

**Request**:
- **Headers**:
  - `Authorization: Bearer {jwt_token}` (required)
- **Path Parameters**:
  - `batchId` (UUID, required): Batch identifier
- **Body**: None

**Responses**:

### 200 OK - Batch completed
```json
{
  "batchId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "COMPLETED",
  "completedAt": "2025-10-06T10:45:00Z",
  "uploadedFilesCount": 15,
  "totalSize": 45678900
}
```

### 400 Bad Request - Batch already completed
```json
{
  "timestamp": "2025-10-06T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Batch is already completed",
  "path": "/api/v1/batch/{batchId}/complete"
}
```

### 401 Unauthorized - Batch does not belong to site
```json
{
  "timestamp": "2025-10-06T10:30:00Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Batch does not belong to this site",
  "path": "/api/v1/batch/{batchId}/complete"
}
```

**Business Rules**:
- Batch must be in IN_PROGRESS status
- Batch must belong to authenticated site
- After completion, no more file uploads allowed
- Transition to COMPLETED status

---

## POST /api/v1/batch/{batchId}/fail

**Summary**: Mark batch as failed due to critical error

**Request**:
- **Headers**:
  - `Authorization: Bearer {jwt_token}` (required)
- **Path Parameters**:
  - `batchId` (UUID, required): Batch identifier
- **Body**:
```json
{
  "reason": "Critical error during file processing"
}
```

**Responses**:

### 200 OK - Batch marked as failed
```json
{
  "batchId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "FAILED"
}
```

**Business Rules**:
- Batch must be in IN_PROGRESS status
- Set `hasErrors = true`
- Transition to FAILED status

---

## POST /api/v1/batch/{batchId}/cancel

**Summary**: Cancel batch (uploaded files remain in S3)

**Request**:
- **Headers**:
  - `Authorization: Bearer {jwt_token}` (required)
- **Path Parameters**:
  - `batchId` (UUID, required): Batch identifier
- **Body**: None

**Responses**:

### 200 OK - Batch cancelled
```json
{
  "batchId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "CANCELLED"
}
```

**Business Rules**:
- Batch must be in IN_PROGRESS status
- Transition to CANCELLED status
- Files remain in S3 storage

---

**OpenAPI Specification**:
```yaml
/api/v1/batch/start:
  post:
    summary: Start new batch
    operationId: startBatch
    tags:
      - Batch
    security:
      - bearerAuth: []
    responses:
      '200':
        description: Batch created successfully
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/BatchStartResponse'
      '401':
        $ref: '#/components/responses/Unauthorized'
      '409':
        $ref: '#/components/responses/Conflict'

/api/v1/batch/{batchId}/upload:
  post:
    summary: Upload files to batch
    operationId: uploadFiles
    tags:
      - Batch
    security:
      - bearerAuth: []
    parameters:
      - name: batchId
        in: path
        required: true
        schema:
          type: string
          format: uuid
    requestBody:
      content:
        multipart/form-data:
          schema:
            type: object
            properties:
              files:
                type: array
                items:
                  type: string
                  format: binary
    responses:
      '200':
        description: Files uploaded successfully
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/UploadResponse'
      '400':
        $ref: '#/components/responses/BadRequest'
      '404':
        $ref: '#/components/responses/NotFound'
      '413':
        $ref: '#/components/responses/PayloadTooLarge'

/api/v1/batch/{batchId}/complete:
  post:
    summary: Complete batch
    operationId: completeBatch
    tags:
      - Batch
    security:
      - bearerAuth: []
    parameters:
      - name: batchId
        in: path
        required: true
        schema:
          type: string
          format: uuid
    responses:
      '200':
        description: Batch completed successfully
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/BatchCompleteResponse'
      '400':
        $ref: '#/components/responses/BadRequest'
      '401':
        $ref: '#/components/responses/Unauthorized'
      '404':
        $ref: '#/components/responses/NotFound'

components:
  schemas:
    BatchStartResponse:
      type: object
      required:
        - batchId
      properties:
        batchId:
          type: string
          format: uuid

    UploadResponse:
      type: object
      required:
        - status
        - uploadedFiles
        - files
      properties:
        status:
          type: string
          example: "OK"
        uploadedFiles:
          type: integer
          example: 2
        files:
          type: array
          items:
            $ref: '#/components/schemas/UploadedFileInfo'

    UploadedFileInfo:
      type: object
      properties:
        fileName:
          type: string
        fileSize:
          type: integer
          format: int64
        uploadedAt:
          type: string
          format: date-time

    BatchCompleteResponse:
      type: object
      required:
        - batchId
        - status
        - completedAt
        - uploadedFilesCount
        - totalSize
      properties:
        batchId:
          type: string
          format: uuid
        status:
          type: string
          example: "COMPLETED"
        completedAt:
          type: string
          format: date-time
        uploadedFilesCount:
          type: integer
        totalSize:
          type: integer
          format: int64
```

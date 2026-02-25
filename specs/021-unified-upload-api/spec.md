# Feature Specification: Unified Data Upload API

**Feature Branch**: `021-unified-upload-api`
**Created**: 2026-02-25
**Status**: Draft
**Input**: Implementation extracted from `docs/unified-data-upload-api.md` — new site types (MSSQL_CDC, DBF_CDC), heartbeat endpoint, client diagnostic logs, admin force-rebaseline, mandatory schema enforcement, unified CDC SQL generation, and admin UI for site management, client logs, and force-rebaseline.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - MSSQL CDC Client Uploads Data (Priority: P1)

A client application connected to an MS SQL Server database needs to send change data (inserts, updates, deletes) to the server. The client authorizes with `siteType: MSSQL_CDC`, submits a table schema, uploads a CSV baseline, and then sends JSONL delta files for subsequent batches. The server processes the JSONL deltas and generates SQL statements.

**Why this priority**: This is the primary business driver — supporting MS SQL Server as a new data source. Without this, the unified API has no new value.

**Independent Test**: Can be fully tested by authorizing a new device with `siteType: MSSQL_CDC`, submitting a schema, uploading a CSV baseline batch, then uploading a JSONL delta batch and verifying SQL is generated correctly.

**Acceptance Scenarios**:

1. **Given** a new device, **When** it authorizes with `siteType: MSSQL_CDC`, **Then** the site is created with the MSSQL_CDC type.
2. **Given** an MSSQL_CDC site with a submitted schema and completed baseline batch, **When** the client uploads a JSONL delta file containing INSERT/UPDATE/DELETE records and completes the batch, **Then** the server generates correct SQL statements from the JSONL deltas.
3. **Given** an MSSQL_CDC site without a submitted schema, **When** the client attempts to start a batch, **Then** the server rejects the request with a `SchemaRequiredException` (400).
4. **Given** a JSONL file with a missing `op` field on a record, **When** the server processes the file, **Then** the entire file is rejected with `InvalidJsonlException` (400).
5. **Given** a JSONL file with a malformed JSON line, **When** the server processes the file, **Then** the malformed line is skipped with a warning and remaining records are processed.

---

### User Story 2 - Heartbeat Pre-Batch Sync Check (Priority: P1)

Before each data upload cycle, the client application calls a heartbeat endpoint to receive directives from the server. The server can instruct the client to perform a full CSV re-upload (force rebaseline) or to upload diagnostic logs. The client must call heartbeat before every batch start; the server rejects batch starts without a recent heartbeat.

**Why this priority**: The heartbeat is a foundational mechanism enabling server-to-client communication (force rebaseline, log requests). It is required for all subsequent batch operations across all site types.

**Independent Test**: Can be tested by calling the heartbeat endpoint and verifying the response contains site status, directives, schema info, and last batch info. Then verifying that batch start is rejected if heartbeat was not called recently.

**Acceptance Scenarios**:

1. **Given** an active site with no special directives, **When** the client calls `GET /heartbeat`, **Then** the server returns site status, `forceFullUpload: false`, schema version, last completed batch info, and server time.
2. **Given** a site with `forceFullUpload` flag set to true, **When** the client calls `GET /heartbeat`, **Then** the response includes `forceFullUpload: true` with the reason.
3. **Given** a site with `requestLogs` flag set to true, **When** the client calls `GET /heartbeat`, **Then** the response includes `requestLogs: true` with a message.
4. **Given** a client that has NOT called heartbeat within the configured time window (default 5 minutes), **When** it attempts `POST /batches/start`, **Then** the server rejects with 428 "Heartbeat required before starting a batch".
5. **Given** a site with `forceFullUpload: true`, **When** the client calls `POST /batches/start`, **Then** the server immediately clears the `forceFullUpload` flag to `false`.
6. **Given** an inactive site, **When** the client calls `GET /heartbeat`, **Then** the response includes `siteStatus: INACTIVE`.

---

### User Story 3 - Admin Forces Rebaseline (Priority: P2)

An administrator detects data corruption or needs to reinitialize a site's data. Through the admin API, they trigger a force-rebaseline command for a specific site. On the next heartbeat, the client receives the directive to upload full CSV files instead of JSONL deltas.

**Why this priority**: Force-rebaseline is a critical operational recovery tool. It depends on the heartbeat mechanism (P1) and is essential for production operations.

**Independent Test**: Can be tested by calling the admin force-rebaseline endpoint and verifying the site's `forceFullUpload` flag is set, then checking the heartbeat response reflects the directive.

**Acceptance Scenarios**:

1. **Given** an active CDC site, **When** an admin calls `POST /admin/sites/{siteId}/force-rebaseline` with a reason, **Then** the site's `forceFullUpload` flag is set to `true` with the reason, timestamp, and admin identity stored.
2. **Given** a non-existent site ID, **When** an admin calls force-rebaseline, **Then** the server returns 404.
3. **Given** a user with `ROLE_USER` (not admin), **When** they call force-rebaseline, **Then** the server returns 403.

---

### User Story 4 - Client Uploads Diagnostic Logs (Priority: P2)

When the client application encounters problems or when an admin requests logs via the heartbeat, the client uploads its log files to the server. Logs are stored for 30 days and accessible to administrators for remote troubleshooting.

**Why this priority**: Diagnostic logs enable remote troubleshooting of client issues, significantly reducing support time. It depends on the heartbeat mechanism for admin-triggered log requests.

**Independent Test**: Can be tested by uploading a log file via `POST /device/logs` and verifying the file is stored and accessible through the admin list/download endpoints.

**Acceptance Scenarios**:

1. **Given** an authenticated client, **When** it uploads a `.log.gz` file with metadata (client version, OS, tags, description), **Then** the server stores the file, records metadata, and returns a `201 Created` response with log ID and expiry date (30 days).
2. **Given** a log file larger than 10 MB, **When** the client attempts upload, **Then** the server rejects with 413 `MaxUploadSizeExceededException`.
3. **Given** a site that has already uploaded 10 logs today, **When** another log upload is attempted, **Then** the server rejects with 429 `LogUploadLimitExceededException`.
4. **Given** a file with an unsupported type (e.g., `.json`, `.exe`), **When** the client attempts upload, **Then** the server rejects with 400 `InvalidLogFileException`.
5. **Given** an empty file, **When** the client attempts upload, **Then** the server rejects with 400.

---

### User Story 5 - Admin Views and Downloads Client Logs (Priority: P3)

Administrators can browse and download client diagnostic logs through the admin API. They can list logs for a specific site with pagination and download individual log files.

**Why this priority**: Viewing logs is the consumption side of the diagnostic logs feature. It provides the actual troubleshooting value but depends on log upload (P2) being implemented first.

**Independent Test**: Can be tested by listing logs for a site and downloading a specific log file, verifying correct pagination and file content.

**Acceptance Scenarios**:

1. **Given** a site with uploaded logs, **When** an admin calls `GET /admin/sites/{siteId}/client-logs?page=0&size=20`, **Then** the server returns a paginated list of log entries with metadata.
2. **Given** a specific log ID, **When** an admin calls `GET /admin/sites/{siteId}/client-logs/{logId}/download`, **Then** the server returns the log file content (or a redirect to a presigned download URL).
3. **Given** a user with `ROLE_USER`, **When** they access the client logs endpoint, **Then** they can view logs for sites belonging to their account.

---

### User Story 6 - DBF CDC Delta Mode (Priority: P3)

Existing DBF clients want the option to send JSONL deltas instead of full CSV snapshots to reduce upload size and server-side diff cost. A client can create a new site with `siteType: DBF_CDC` and follow the standard CDC flow (CSV baseline + JSONL deltas).

**Why this priority**: This is an optimization for existing DBF clients. The core CDC pipeline (built for MSSQL_CDC in P1) already supports this with minimal additional work.

**Independent Test**: Can be tested by authorizing a device with `siteType: DBF_CDC`, uploading a CSV baseline, then uploading JSONL deltas and verifying SQL generation.

**Acceptance Scenarios**:

1. **Given** a new device, **When** it authorizes with `siteType: DBF_CDC`, **Then** the site is created with the DBF_CDC type.
2. **Given** a DBF_CDC site with a completed baseline, **When** the client uploads JSONL deltas, **Then** the server generates SQL from the deltas using the same CDC strategy as other CDC types.

---

### User Story 7 - Mandatory Schema for DBF Sites (Priority: P2)

Schema submission becomes mandatory for all site types, including existing DBF (snapshot) sites. A grace period allows existing clients time to update. During the grace period, the server logs a warning but allows DBF batches without schema. After the grace period, batches without schema are rejected.

**Why this priority**: Schema enforcement ensures correct SQL generation and validation for all site types. The grace period protects backward compatibility with existing clients.

**Independent Test**: Can be tested by attempting to start a DBF batch without a schema and verifying the behavior matches the current grace period phase (warning vs rejection).

**Acceptance Scenarios**:

1. **Given** a DBF site during the grace period that has no schema, **When** the client starts a batch, **Then** the server logs a warning but allows the batch to proceed.
2. **Given** a DBF site after the grace period that has no schema, **When** the client starts a batch, **Then** the server rejects with 400 `SchemaRequiredException`.
3. **Given** a DBF site with a submitted schema, **When** the client starts a batch, **Then** the batch proceeds normally regardless of grace period status.

---

### User Story 8 - Batch Type Required on Batch Start (Priority: P2)

The batch start request must include a `batchType` field (`BASELINE` or `DELTA`) so the server knows how to process the uploaded files and whether to generate SQL.

**Why this priority**: The batch type drives processing logic — baseline batches store data without SQL generation, delta batches trigger SQL generation. This is required for correct CDC behavior.

**Independent Test**: Can be tested by starting batches with and without `batchType` and verifying correct validation and processing behavior.

**Acceptance Scenarios**:

1. **Given** a valid batch start request with `batchType: DELTA`, **When** the client starts a batch, **Then** the batch is created with DELTA type.
2. **Given** a valid batch start request with `batchType: BASELINE`, **When** the client starts a batch, **Then** the batch is created as a baseline (no SQL generation).
3. **Given** a batch start request without `batchType`, **When** the client starts a batch, **Then** the server rejects with 400 "batchType is required".

---

### User Story 9 - Admin Forces Rebaseline via UI (Priority: P2)

An administrator opens a site's detail page in the admin dashboard. For CDC-type sites, they see a "Force Rebaseline" button. Clicking it opens a confirmation dialog where they enter a reason. After confirmation, the system sets the directive and shows a success notification. The site detail page reflects the active directive status.

**Why this priority**: This is the UI counterpart of the backend force-rebaseline (US3). Without it, admins would need to use raw API calls. It is essential for production operations.

**Independent Test**: Can be tested by navigating to a CDC site's detail page, clicking "Force Rebaseline", entering a reason, confirming, and verifying the success notification and updated directive status on the page.

**Acceptance Scenarios**:

1. **Given** an admin viewing a CDC site's detail page, **When** the page loads, **Then** the admin sees a "Force Rebaseline" button in the site actions area.
2. **Given** an admin viewing a DBF (SNAPSHOT) site, **When** the page loads, **Then** the "Force Rebaseline" button is NOT shown (full snapshot sites don't need it).
3. **Given** a CDC site detail page, **When** the admin clicks "Force Rebaseline", **Then** a confirmation dialog appears with a required reason text field and a warning about the operation.
4. **Given** the confirmation dialog, **When** the admin enters a reason and confirms, **Then** the system calls the API, shows a success toast, and the site detail page updates to show the active `forceFullUpload` directive with the reason.
5. **Given** a site with an active `forceFullUpload` directive, **When** the admin views the site detail page, **Then** a visible indicator (badge or alert) shows the pending directive with reason and timestamp.

---

### User Story 10 - Admin Requests Client Logs via UI (Priority: P3)

An administrator needs diagnostic logs from a client. On the site detail page, they click "Request Logs", optionally enter a message, and confirm. On the next heartbeat, the client receives the directive and uploads its logs.

**Why this priority**: This completes the log request workflow from the admin side. Lower priority because admins can still request logs via API.

**Independent Test**: Can be tested by clicking "Request Logs" on a site detail page, confirming, and verifying the directive is set.

**Acceptance Scenarios**:

1. **Given** an admin viewing a site detail page, **When** the page loads, **Then** the admin sees a "Request Logs" button.
2. **Given** the "Request Logs" button clicked, **When** the admin enters an optional message and confirms, **Then** the system sets the `requestLogs` directive and shows a success toast.
3. **Given** a site with `requestLogs` already active, **When** the admin views the page, **Then** the button is disabled or shows a "Logs requested" indicator.

---

### User Story 11 - Admin Views Client Logs in UI (Priority: P2)

An administrator navigates to a site's detail page and opens the "Client Logs" tab. They see a paginated list of uploaded diagnostic log files with metadata (filename, size, client version, OS, tags, upload date). They can download individual log files.

**Why this priority**: This is the primary way admins will troubleshoot client issues. It depends on the backend log storage (US4, US5) and is essential for the diagnostic workflow.

**Independent Test**: Can be tested by navigating to a site with uploaded logs, viewing the Client Logs tab, verifying the log entries are displayed with correct metadata, and downloading a log file.

**Acceptance Scenarios**:

1. **Given** a site with uploaded diagnostic logs, **When** the admin opens the "Client Logs" tab on the site detail page, **Then** a paginated table of log entries is displayed with columns: filename, file size, client version, OS, tags, description, upload date.
2. **Given** a log entry in the table, **When** the admin clicks the download button, **Then** the log file is downloaded to their browser.
3. **Given** more than 20 log entries, **When** the admin views the table, **Then** pagination controls (previous/next) are available.
4. **Given** no uploaded logs for the site, **When** the admin opens the "Client Logs" tab, **Then** an empty state message is shown (e.g., "No diagnostic logs uploaded yet").
5. **Given** log entries with tags, **When** the admin views the table, **Then** tags are displayed as badges.

---

### User Story 12 - New Site Types Displayed in Site List (Priority: P2)

The site list in the admin dashboard displays the correct site type badge for all four site types. New site types (MSSQL_CDC, DBF_CDC) are visually distinguished with appropriate labels and styling.

**Why this priority**: Without correct badge rendering, new site types would show incorrectly or be missing in the site list, causing confusion.

**Independent Test**: Can be tested by viewing a site list that contains sites of all four types and verifying each has the correct badge label and styling.

**Acceptance Scenarios**:

1. **Given** a site with `siteType: MSSQL_CDC`, **When** it appears in the site list, **Then** it shows a badge labeled "MSSQL CDC" with appropriate styling.
2. **Given** a site with `siteType: DBF_CDC`, **When** it appears in the site list, **Then** it shows a badge labeled "DBF CDC" with appropriate styling.
3. **Given** existing DBF and POSTGRES_CDC sites, **When** they appear in the site list, **Then** their badges are unchanged from current behavior.

---

### User Story 13 - Heartbeat & Directive Status on Site Detail (Priority: P3)

The site detail page shows the site's heartbeat status (last heartbeat time) and any active directives. This gives the admin visibility into whether the client is actively syncing and whether pending directives have been picked up.

**Why this priority**: Provides operational visibility. Lower priority because the data is available via API and the directive actions (US9, US10) already show status.

**Independent Test**: Can be tested by viewing a site detail page for a site that has recent heartbeat data and active directives.

**Acceptance Scenarios**:

1. **Given** a site with recent heartbeat data, **When** the admin views the site detail page, **Then** the last heartbeat time is displayed (e.g., "Last heartbeat: 2 minutes ago").
2. **Given** a site that has not sent a heartbeat, **When** the admin views the detail page, **Then** the heartbeat field shows "Never" or "No heartbeat recorded".
3. **Given** a site with `forceFullUpload: true`, **When** the admin views the detail page, **Then** an alert or badge indicates the pending force-rebaseline directive with reason.
4. **Given** a site with `requestLogs: true`, **When** the admin views the detail page, **Then** an indicator shows the pending log request.

---

### User Story 14 - Batch Type Displayed in Batch History (Priority: P3)

The batch list in the upload history and SQL tabs shows the batch type (BASELINE or DELTA) for each batch. This helps admins understand the data flow.

**Why this priority**: Informational improvement. Low priority because the batch type is a new concept and doesn't block any workflow.

**Independent Test**: Can be tested by viewing the batch list for a CDC site that has both baseline and delta batches.

**Acceptance Scenarios**:

1. **Given** a batch with `batchType: BASELINE`, **When** it appears in the batch list, **Then** it shows a "Baseline" badge.
2. **Given** a batch with `batchType: DELTA`, **When** it appears in the batch list, **Then** it shows a "Delta" badge.
3. **Given** older batches without a `batchType` (migrated data), **When** they appear in the list, **Then** no batch type badge is shown (graceful handling of null).

---

### Edge Cases

- What happens when a CDC client uploads a CSV file (instead of JSONL) in a non-baseline batch? The server should treat it as a new baseline (full snapshot replacement), with no SQL generated.
- What happens when a JSONL file references a table not in the schema? The file is skipped with a warning; the batch continues.
- What happens when a JSONL file contains an unknown column? The column is skipped with a warning; the record is processed.
- What happens when the heartbeat flag `forceFullUpload` is set for a DBF (SNAPSHOT) site? The flag is always `false` for DBF sites (every batch is already a full snapshot).
- What happens when schema is updated while a batch is in progress? The in-progress batch uses the schema version pinned at batch start time; the new schema applies to future batches.
- What happens when `forceFullUpload` is set but the client starts a batch and then fails/cancels it? The flag was already cleared at batch start. The admin would need to set it again.
- What happens when a client uploads logs with overlapping time periods? Both uploads are accepted independently; the server does not deduplicate.
- What happens when a log file retention period expires? The system automatically deletes the file from storage and its metadata from the database.
- What happens when the admin clicks "Force Rebaseline" while a `forceFullUpload` directive is already active? The dialog should inform the admin that a directive is already pending and allow them to update the reason.
- What happens when the browser loses connectivity while downloading a client log file? Standard browser download behavior applies (presigned URL has 15-minute expiry for retry).
- What happens when there are many sites of different types in the list? The site type badge styling must be distinct enough for quick visual scanning across all four types.

## Requirements *(mandatory)*

### Functional Requirements

#### Site Types & Authorization

- **FR-001**: System MUST support four site types: `DBF`, `POSTGRES_CDC`, `MSSQL_CDC`, `DBF_CDC`.
- **FR-002**: System MUST accept an optional `siteType` field during device authorization, defaulting to `DBF` if omitted.
- **FR-003**: System MUST persist the site type and use it to determine upload mode (SNAPSHOT vs CDC), file format validation, and SQL generation strategy.

#### Schema Enforcement

- **FR-004**: System MUST require schema submission before the first batch for all site types (`DBF`, `POSTGRES_CDC`, `MSSQL_CDC`, `DBF_CDC`).
- **FR-005**: System MUST reject batch start requests with 400 `SchemaRequiredException` if no schema has been submitted (after grace period for DBF sites).
- **FR-006**: System MUST support a configurable grace period for DBF sites, during which batches without schema are allowed with a logged warning.
- **FR-007**: System MUST accept MSSQL-specific type aliases in schema (e.g., `nvarchar` → `VARCHAR`, `datetime2` → `TIMESTAMP`, `uniqueidentifier` → `UUID`, `money` → `MONEY`).
- **FR-008**: System MUST pin the schema version at batch start time and use the pinned version for SQL generation.

#### Heartbeat

- **FR-009**: System MUST provide a `GET /api/v1/device/heartbeat` endpoint that returns site status, directives (`forceFullUpload`, `requestLogs`), schema version, last completed batch info, and server time.
- **FR-010**: System MUST reject `POST /batches/start` with 428 if heartbeat was not called within the configurable time window (default 5 minutes).
- **FR-011**: System MUST track the last heartbeat timestamp per site.
- **FR-012**: When `forceFullUpload` is `true` and the client calls `POST /batches/start`, the system MUST immediately clear the flag to `false`.
- **FR-013**: System MUST always return `forceFullUpload: false` for `DBF` (SNAPSHOT) sites.

#### Force Rebaseline (Admin)

- **FR-014**: System MUST provide `POST /api/v1/admin/sites/{siteId}/force-rebaseline` endpoint for admins (`ROLE_ADMIN`) to set `forceFullUpload: true` with a reason and message.
- **FR-015**: System MUST record who set the flag, when, and the reason (`ADMIN_REQUEST`, `PLUGIN_REINIT`, `SCHEMA_INCOMPATIBLE`, `DATA_CORRUPTION`).
- **FR-016**: Plugin reinitialization MUST automatically set `forceFullUpload: true` with reason `PLUGIN_REINIT`.

#### Client Diagnostic Logs

- **FR-017**: System MUST provide `POST /api/v1/device/logs` endpoint for clients to upload log files with metadata (client version, OS, period, tags, description).
- **FR-018**: System MUST accept only plain text log files: `.log`, `.log.gz`, `.txt`, `.txt.gz`.
- **FR-019**: System MUST enforce a 10 MB maximum file size for log uploads.
- **FR-020**: System MUST enforce a rate limit of 10 log uploads per site per day.
- **FR-021**: System MUST store log files in cloud storage with a 30-day retention period, after which they are automatically deleted.
- **FR-022**: System MUST clear the `requestLogs` directive flag after logs are successfully received.

#### Admin Log Viewer

- **FR-023**: System MUST provide `GET /api/v1/admin/sites/{siteId}/client-logs` endpoint with pagination.
- **FR-024**: System MUST provide `GET /api/v1/admin/sites/{siteId}/client-logs/{logId}/download` endpoint that returns the log file or a presigned download URL.
- **FR-025**: Both admin log endpoints MUST be accessible to `ROLE_ADMIN` and `ROLE_USER` (scoped to their account's sites).

#### JSONL Processing & Validation

- **FR-026**: System MUST parse JSONL delta files with three operation types: `I` (Insert), `U` (Update), `D` (Delete).
- **FR-027**: System MUST reject the entire file (fatal error, 400) when a record has: missing `op` field, unknown `op` value, missing `k` on UPDATE/DELETE, or missing `d` on INSERT.
- **FR-028**: System MUST skip with a warning (non-fatal): unknown columns in `d` or `k`, tables not in schema, malformed JSON lines.
- **FR-029**: System MUST process JSONL records in order (line by line) and files in upload order.

#### SQL Generation

- **FR-030**: All CDC site types (`POSTGRES_CDC`, `MSSQL_CDC`, `DBF_CDC`) MUST share the same CDC SQL generation strategy.
- **FR-031**: All generated SQL MUST use a single dialect (PostgreSQL syntax) regardless of the source site type.
- **FR-032**: Baseline batches (first batch for CDC sites, or forced full upload) MUST NOT generate SQL.
- **FR-033**: System MUST correctly generate INSERT, UPDATE, and DELETE SQL from JSONL records, including NULL handling and composite primary keys.

#### Batch Lifecycle

- **FR-034**: Batch start request MUST include a `batchType` field (`BASELINE` or `DELTA`); omitting it results in 400.
- **FR-035**: For CDC sites, uploading CSV files in a non-baseline batch MUST be treated as a new baseline (no SQL generated).

#### File Validation

- **FR-036**: System MUST accept only gzip-compressed files (`.csv.gz`, `.jsonl.gz`); uncompressed files MUST be rejected with 400.
- **FR-037**: System MUST validate uploaded file types against the site type and batch type (e.g., DBF sites accept only CSV.GZ; CDC delta batches accept JSONL.GZ).

#### UI — Site Management

- **FR-038**: Site list MUST display correct site type badges for all four site types (`DBF`, `POSTGRES_CDC`, `MSSQL_CDC`, `DBF_CDC`) with distinct visual styling.
- **FR-039**: Site detail page MUST display the last heartbeat time for the site (relative time, e.g., "2 minutes ago" or "Never").
- **FR-040**: Site detail page MUST display active directives (`forceFullUpload`, `requestLogs`) with reason and timestamp when active.

#### UI — Force Rebaseline

- **FR-041**: Site detail page MUST show a "Force Rebaseline" action button for CDC-type sites only (`POSTGRES_CDC`, `MSSQL_CDC`, `DBF_CDC`). The button MUST NOT be shown for `DBF` (SNAPSHOT) sites.
- **FR-042**: Clicking "Force Rebaseline" MUST open a confirmation dialog with a required reason text field and a warning about the operation's impact.
- **FR-043**: After successful force-rebaseline, the system MUST show a success toast notification and refresh the site detail to reflect the active directive.

#### UI — Request Client Logs

- **FR-044**: Site detail page MUST show a "Request Logs" action button.
- **FR-045**: Clicking "Request Logs" MUST open a dialog with an optional message field and a confirm button.
- **FR-046**: When `requestLogs` is already active for the site, the button MUST be disabled or show a "Logs Requested" indicator.

#### UI — Client Logs Viewer

- **FR-047**: Site detail page MUST include a "Client Logs" tab (or section) displaying uploaded diagnostic logs.
- **FR-048**: Client logs list MUST show: filename, file size (human-readable), client version, OS, tags (as badges), description (truncated), and upload date.
- **FR-049**: Each log entry MUST have a download button that triggers a file download via presigned URL.
- **FR-050**: Client logs list MUST support pagination (previous/next, page size selector).
- **FR-051**: Empty state MUST display a message when no logs have been uploaded for the site.

#### UI — Batch Type Display

- **FR-052**: Batch list (upload history and SQL tabs) MUST display batch type badges: "Baseline" or "Delta" for batches that have a `batchType` value.
- **FR-053**: Batches without a `batchType` (legacy data) MUST be displayed without a badge (graceful null handling).

### Key Entities

- **Site**: Represents a client data source. Extended with `siteType` (DBF, POSTGRES_CDC, MSSQL_CDC, DBF_CDC), `forceFullUpload` flag and related metadata (`reason`, `message`, `setAt`, `setBy`), `requestLogs` flag, `lastHeartbeatAt` timestamp.
- **Batch**: Upload session. Extended with `batchType` (BASELINE, DELTA) and `schemaVersion` (pinned at batch start).
- **Schema**: Table definitions (columns, types, primary keys, unique keys) for a site. Already exists; extended with MSSQL type alias support.
- **Client Diagnostic Log**: A log file uploaded by a client for troubleshooting. Contains file reference, metadata (client version, OS, period, tags, description), and retention expiry date.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An MSSQL CDC client can complete the full flow (authorize → schema → baseline → delta → SQL generation) without errors.
- **SC-002**: All existing PostgreSQL CDC clients continue to function identically without any changes on their side.
- **SC-003**: Existing DBF clients continue to function during the grace period without schema submission; after grace period, they receive a clear error message guiding them to submit schema.
- **SC-004**: The heartbeat endpoint responds within 200ms under normal load (single query to sites table + optional join).
- **SC-005**: An administrator can trigger force-rebaseline and see the directive reflected in the client's next heartbeat call within the normal sync cycle.
- **SC-006**: Client log files are available for admin download within seconds of upload, and automatically cleaned up after 30 days.
- **SC-007**: The CDC SQL generation strategy produces identical output for the same JSONL input regardless of whether the site type is POSTGRES_CDC, MSSQL_CDC, or DBF_CDC.
- **SC-008**: All new endpoints follow existing authentication patterns (Custom JWT for device API, Auth0 OAuth2 for admin API).
- **SC-009**: An administrator can force-rebaseline a CDC site through the UI in under 30 seconds (navigate to site → click button → enter reason → confirm).
- **SC-010**: An administrator can view and download client diagnostic logs through the site detail page without needing API tools.
- **SC-011**: All four site types are visually distinguishable in the site list at a glance.
- **SC-012**: Heartbeat status and active directives are visible on the site detail page, providing real-time operational awareness.

## Assumptions

- The existing `POSTGRES_CDC` JSONL format and SQL generation strategy (implemented in feature 019) serve as the foundation. This feature extends it to be site-type-agnostic.
- The heartbeat time window (5 minutes default) is configurable via server settings.
- The grace period for DBF schema enforcement is configurable by admins.
- Log file retention cleanup is handled by a scheduled task (similar to existing batch retention cleanup).
- The `requestLogs` flag clearing mechanism is similar to `forceFullUpload` — cleared when the server receives a log upload from the site.
- Presigned URLs for log file download follow the same pattern as existing S3 file downloads (15-minute expiry).

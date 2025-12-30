# Feature Specification: Plugin SQL Generation Extension

**Feature Branch**: `001-plugin-sql-generation`
**Created**: 2025-12-22
**Status**: Draft
**Extends**: PRD-013 (Plugin System & Bit BI OAuth Integration)
**Input**: User description: "Extension to plugin system for automatic SQL file generation based on sequential batch upload differences"

## Overview

This feature extends the existing plugin system (PRD-013) to automatically generate PostgreSQL SQL files when batch uploads complete. The system compares files between consecutive batches for the same site and produces SQL statements (INSERT, UPDATE, DELETE) reflecting the differences. A Plugin API allows Bit BI users to retrieve these SQL changes by site and date.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Automatic Diff Generation on Batch Completion (Priority: P1)

When a batch upload completes for an account with the Bit BI plugin activated, the system automatically compares all files in the current batch against the previous batch for the same site and generates SQL statements.

**Why this priority**: This is the core automation that enables the entire feature. Without automatic diff generation, no SQL files can be created.

**Independent Test**: Can be fully tested by uploading two consecutive batches to a site with Bit BI plugin active and verifying SQL file generation in S3.

**Acceptance Scenarios**:

1. **Given** an account with activated Bit BI plugin, **When** a batch upload completes (`BATCH_COMPLETED` event), **Then** the system automatically generates a diff between current batch files and previous batch files for the same site.

2. **Given** this is the first batch for a site, **When** the batch upload completes, **Then** INSERT statements are generated for all rows in all CSV files.

3. **Given** a previous batch exists for the site, **When** diff is executed, **Then** the result contains added rows (INSERT), modified rows (UPDATE), and deleted rows (DELETE).

4. **Given** the Bit BI plugin is deactivated for the account, **When** a batch upload completes, **Then** no diff or SQL generation occurs.

---

### User Story 2 - SQL File Generation from Diff Results (Priority: P1)

Based on the diff results, the system generates PostgreSQL SQL files with properly formatted INSERT, UPDATE, and DELETE statements.

**Why this priority**: Without SQL generation, the diff results cannot be consumed by downstream systems. This is essential for delivering value.

**Independent Test**: Can be tested by providing mock diff results and verifying the generated SQL statements match expected format and content.

**Acceptance Scenarios**:

1. **Given** a diff shows an added row, **When** SQL is generated, **Then** an `INSERT INTO {table} (...) VALUES (...)` statement is created with all column values.

2. **Given** a diff shows a modified row, **When** SQL is generated, **Then** an `UPDATE {table} SET changed_col=new_val WHERE unchanged_col1=val1 AND unchanged_col2=val2` statement is created using unchanged fields in the WHERE clause.

3. **Given** a diff shows a deleted row, **When** SQL is generated, **Then** a `DELETE FROM {table} WHERE col1=val1 AND col2=val2 AND ...` statement is created using all fields in the WHERE clause.

4. **Given** any SQL statement is generated, **When** formatting is applied, **Then** a comment `--- END OF COMMAND "{filename}:{line_number}" ---` is appended after each statement.

5. **Given** an empty string value in a Character/Numeric/Date type field, **When** SQL is generated, **Then** the value is represented as NULL.

6. **Given** an empty string value in an Integer or Currency type field, **When** SQL is generated, **Then** the value is represented as 0 (not NULL).

---

### User Story 3 - Retrieve SQL Changes via Plugin API (Priority: P1)

Bit BI users with a valid Plugin API Key can retrieve all SQL changes for a specific site after a given date through an API endpoint.

**Why this priority**: This is the primary way consumers access the generated SQL. Without this endpoint, the generated files have no practical use.

**Independent Test**: Can be tested by making API requests with valid/invalid credentials and verifying correct responses and authorization.

**Acceptance Scenarios**:

1. **Given** a valid Plugin API Key, **When** requesting `GET /sql-changes?siteId=X&since=2025-01-01T00:00:00Z`, **Then** all SQL changes for site X after the specified date are returned as plain text.

2. **Given** an invalid API Key, **When** the request is executed, **Then** 401 Unauthorized is returned.

3. **Given** a valid API Key but siteId does not belong to the account, **When** the request is executed, **Then** 403 Forbidden is returned.

4. **Given** no changes exist after the specified date, **When** the request is executed, **Then** 200 OK with empty body is returned.

5. **Given** multiple SQL files exist for the site after the date, **When** the request is executed, **Then** all files are concatenated and returned in chronological order.

---

### User Story 4 - List Available Sites via Plugin API (Priority: P2)

Bit BI users can retrieve a list of sites available for their account through the Plugin API.

**Why this priority**: Helps users discover which sites they can query. Useful but not essential for core functionality.

**Independent Test**: Can be tested by making API request with valid API Key and verifying site list matches account's sites.

**Acceptance Scenarios**:

1. **Given** a valid Plugin API Key, **When** requesting `GET /sites`, **Then** a list of all sites belonging to the account is returned with id, name, and domain.

2. **Given** an invalid API Key, **When** the request is executed, **Then** 401 Unauthorized is returned.

---

### User Story 5 - SQL File Storage in S3 (Priority: P2)

Generated SQL files are stored in AWS S3 with a structured path for organization and retrieval.

**Why this priority**: Storage is required for the API to function, but the specific path structure is a detail that can be adjusted.

**Independent Test**: Can be tested by triggering SQL generation and verifying file exists at expected S3 path.

**Acceptance Scenarios**:

1. **Given** an SQL file is generated, **When** saved to S3, **Then** the path follows: `plugins/bit-bi/{accountId}/{siteName}/{source_datetime}--{comparison_datetime}.sql`.

2. **Given** this is the first batch for a site, **When** saved to S3, **Then** the path uses: `plugins/bit-bi/{accountId}/{siteName}/{source_datetime}--first-batch.sql`.

3. **Given** a file is successfully saved, **When** requested through the API, **Then** the content is loaded from S3 and returned to the client.

---

### User Story 6 - Plugin API Key Generation (Priority: P2)

When the Bit BI plugin is activated, a Plugin API Key is generated, returned to the user, and stored (as BCrypt hash) for API authentication.

**Why this priority**: Required for API authentication but can reuse existing plugin activation infrastructure.

**Independent Test**: Can be tested by activating Bit BI plugin and verifying API Key is generated, returned in response, and stored in plugin_data.

**Acceptance Scenarios**:

1. **Given** a user activates the Bit BI plugin, **When** activation completes, **Then** a Plugin API Key is generated in format `plk_` + 32 alphanumeric characters.

2. **Given** a Plugin API Key is generated, **When** stored, **Then** it is saved as BCrypt hash in `account_plugins.plugin_data.apiKeyHash` field.

3. **Given** a new Plugin API Key is generated, **When** the activation response is sent, **Then** the raw API Key is included in the `apiKey` field of the response (shown only once).

4. **Given** an existing active plugin is re-activated (update), **When** the activation response is sent, **Then** the `apiKey` field is null (existing key not exposed).

5. **Given** an existing deactivated plugin is re-activated, **When** activation completes, **Then** a new API Key is generated and returned in the response.

---

### User Story 7 - List Available Tables via Plugin API (Priority: P2)

Bit BI users can retrieve a list of unique table names derived from uploaded CSV files through the Plugin API.

**Why this priority**: Helps users discover which tables are available without needing to make a SQL changes request first.

**Independent Test**: Can be tested by making API request with valid API Key and verifying table list matches account's uploaded file names.

**Acceptance Scenarios**:

1. **Given** a valid Plugin API Key, **When** requesting `GET /tables`, **Then** a list of unique table names is returned with file size and last update timestamp.

2. **Given** multiple batches have uploaded the same file name, **When** the request is executed, **Then** only the latest upload information is returned for each table.

3. **Given** an invalid API Key, **When** the request is executed, **Then** 401 Unauthorized is returned.

4. **Given** no files have been uploaded for the account, **When** the request is executed, **Then** 200 OK with empty tables array is returned.

---

### Edge Cases

- **Empty diff (identical files)**: No SQL file is created, no record in plugin_sql_generations
- **Binary files in batch**: Skipped - only CSV/text files are processed
- **Batch with no files**: No SQL file is created
- **Site deleted after SQL generation**: SQL files remain in S3 for audit purposes
- **File encoding detection**: Auto-detect using ICU4J for UTF-8, Windows-1252, ISO-8859-1
- **Special characters in values**: Properly escaped in SQL statements (single quotes doubled)
- **NULL vs empty string**: Handled according to DBF type rules (see US2 scenarios 5-6)
- **Large files**: Streaming processing to avoid memory issues
- **Concurrent batch completions**: Each processed independently with unique SQL file

## Requirements *(mandatory)*

### Functional Requirements

#### SQL Generation

- **FR-001**: System MUST automatically trigger diff generation when `BATCH_COMPLETED` event occurs for accounts with activated Bit BI plugin
- **FR-002**: System MUST generate `INSERT INTO {table} (...) VALUES (...)` statements for added rows
- **FR-003**: System MUST generate `UPDATE {table} SET ... WHERE ...` statements for modified rows, using unchanged fields in WHERE clause
- **FR-004**: System MUST generate `DELETE FROM {table} WHERE ...` statements for deleted rows, using all fields in WHERE clause
- **FR-005**: System MUST append comment `--- END OF COMMAND "{filename}:{line_number}" ---` after each SQL statement
- **FR-006**: System MUST convert empty strings to NULL for Character, Numeric, Logical, Date, Float, DateTime types
- **FR-007**: System MUST convert empty strings to 0 (not NULL) for Integer and Currency types
- **FR-008**: System MUST derive table names from CSV filenames (without extension)
- **FR-009**: System MUST save SQL files to S3 at path `plugins/bit-bi/{accountId}/{siteName}/{datetime}.sql`

#### Plugin API

- **FR-010**: System MUST provide endpoint `GET /api/v1/plugins/bit-bi/sql-changes` accepting siteId and since parameters
- **FR-011**: System MUST provide endpoint `GET /api/v1/plugins/bit-bi/sites` returning account's sites
- **FR-011a**: System MUST provide endpoint `GET /api/v1/plugins/bit-bi/tables` returning unique table names with latest upload info
- **FR-012**: System MUST validate Plugin API Key on all plugin API requests
- **FR-013**: System MUST verify siteId belongs to the API Key's account before returning data
- **FR-014**: System MUST return 401 Unauthorized for invalid or missing API Key
- **FR-015**: System MUST return 403 Forbidden when siteId does not belong to account
- **FR-016**: System MUST generate Plugin API Key (format: `plk_` + 32 alphanumeric) when Bit BI plugin is activated
- **FR-019**: System MUST return the raw API Key in the activation response for new activations only (not updates)

#### Data Storage

- **FR-017**: System MUST track SQL generations in database with metadata (site, batch IDs, S3 key, file size, statement count, timestamp)
- **FR-018**: System MUST support lookup of SQL generations by site and date range

### Key Entities

- **PluginSqlGeneration**: Tracks each SQL file generation - links to account plugin, site, source batch, comparison batch, S3 location, and metadata
- **Plugin API Key**: Authentication credential stored in account_plugins.plugin_data - bound to account, grants access to all account sites

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: SQL generation completes within 60 seconds for a batch containing 100 CSV files
- **SC-002**: API endpoint `GET /sql-changes` returns response within 2 seconds
- **SC-003**: 100% of row additions, modifications, and deletions are accurately reflected in generated SQL
- **SC-004**: API Key validation completes in under 50 milliseconds
- **SC-005**: All API calls and SQL generations are recorded in audit trail
- **SC-006**: Users can retrieve SQL changes for any site within their account without errors
- **SC-007**: First batch for a site generates valid INSERT statements for all rows

## Assumptions

- The existing plugin infrastructure (PRD-013) is fully implemented and functional
- `PluginEventDispatcher` correctly dispatches `BATCH_COMPLETED` events to registered plugins
- CSV files use standard delimiters (comma, semicolon, or tab) and can be auto-detected
- S3 bucket `dataforge-uploads` exists and is accessible by the application
- DBF type information is available for each column to determine NULL handling rules

## Out of Scope

- Web UI for viewing SQL files (API-only access)
- Support for SQL dialects other than PostgreSQL
- Manual triggering of diff (automatic on BATCH_COMPLETED only)
- Editing generated SQL files
- Regeneration of SQL for existing batches
- Real-time streaming of SQL changes (batch retrieval only)

# Feature Specification: Plugin History Management

**Feature Branch**: `014-plugin-history`
**Created**: 2026-01-01
**Status**: Draft
**Input**: User description: "Clear Plugin History for account + data view. Admin functionality to clear plugin history for a specific user: delete DB history, physical files, and account-plugin connection. View what was generated, when, with optional Regenerate capability."

## Clarifications

### Session 2026-01-01

- Q: How should SQL content be displayed to administrators? → A: Paginated inline view (show first N statements with syntax highlighting) plus download option for full file.
- Q: How many SQL statements per page in inline preview? → A: 100 statements per page.

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - View SQL Generation History (Priority: P1)

As an administrator, I want to view the complete SQL generation history for a specific account's plugin activation, so I can understand what data transformations have occurred and when they happened.

**Why this priority**: Viewing history is the foundational capability - administrators need visibility into generated data before they can take any action (regenerate or clear). This provides value immediately without any destructive operations.

**Independent Test**: Can be fully tested by navigating to an account's plugin history page and verifying all generations are listed with their metadata. Delivers immediate operational visibility.

**Acceptance Scenarios**:

1. **Given** an admin user is viewing a specific account's plugin activations, **When** they select a plugin (e.g., bit-bi), **Then** they see a chronological list of all SQL generations with: generation date, source batch info, comparison batch info (if any), statement counts (inserts/updates/deletes), file size, and duration.

2. **Given** an admin is viewing a plugin's SQL generation history, **When** they click on a specific generation entry, **Then** they see a paginated inline preview of the SQL statements (with syntax highlighting) and can download the full SQL file.

3. **Given** an admin is viewing SQL generation history, **When** a generation was the "first batch" (initial load), **Then** the entry is clearly marked as "Initial Load" (comparison_batch_id is null).

4. **Given** an account has no plugin SQL generations yet, **When** an admin views the history, **Then** they see an empty state message indicating no generations exist.

---

### User Story 2 - Clear Plugin History (Priority: P2)

As an administrator, I want to completely clear all plugin history for a specific account, including database records and S3 files, so I can reset the plugin state for troubleshooting or data cleanup purposes.

**Why this priority**: Clearing history is a critical admin operation that depends on understanding what exists (P1). This enables troubleshooting and data management workflows.

**Independent Test**: Can be tested by clearing a test account's plugin history and verifying all related data is removed from database and S3 storage.

**Acceptance Scenarios**:

1. **Given** an admin is viewing a plugin's history for an account, **When** they initiate "Clear All History", **Then** they are prompted with a confirmation dialog showing what will be deleted (count of generations, total file size, plugin deactivation warning).

2. **Given** an admin confirms the clear operation, **When** the operation completes, **Then** all `plugin_sql_generations` records for that account-plugin are deleted, all associated S3 SQL files are deleted, and the `account_plugins` record is deactivated (soft delete).

3. **Given** an admin clears plugin history, **When** the operation completes, **Then** an audit log entry is created recording the clear action with metadata about what was deleted.

4. **Given** an admin attempts to clear history for an account with active batches in progress, **When** they initiate clear, **Then** the system warns that pending batches exist and asks for additional confirmation.

---

### User Story 3 - Regenerate SQL for a Batch (Priority: P3)

As an administrator, I want to regenerate the SQL for a specific batch, so I can recover from generation errors or apply updated generation logic without requiring the user to re-upload files.

**Why this priority**: Regeneration is an advanced recovery capability that builds on viewing (P1) and provides an alternative to clearing (P2). Most useful for targeted fixes rather than bulk operations.

**Independent Test**: Can be tested by triggering regeneration for a specific batch and verifying new SQL is generated while preserving the original generation record for audit purposes.

**Acceptance Scenarios**:

1. **Given** an admin is viewing a specific SQL generation in the history, **When** they click "Regenerate", **Then** the system creates a new SQL generation for that batch using current generation logic.

2. **Given** an admin triggers regeneration for a batch, **When** the regeneration completes, **Then** a new `plugin_sql_generations` record is created, the old generation record is preserved with a flag indicating it was superseded, and both the old and new S3 files are retained.

3. **Given** a batch's source files no longer exist in S3, **When** an admin attempts to regenerate, **Then** the system displays an error explaining that source CSV files are unavailable.

4. **Given** regeneration is triggered, **When** it completes successfully, **Then** an audit log entry is created with action type "SQL_REGENERATION_COMPLETED" including references to both old and new generation IDs.

---

### Edge Cases

- What happens when an admin tries to clear history while a batch is actively being processed? → Block operation and show warning.
- What happens when S3 file deletion fails for some files during clear? → Continue deleting others, log failures, report partial success with list of failed files.
- What happens when the plugin was already deactivated before clear? → Allow clear operation (history may still exist from before deactivation).
- What happens when regeneration is triggered for a batch whose previous batch no longer exists? → Generate as if it's a first batch (all INSERTs).
- What happens when multiple admins try to clear the same plugin history simultaneously? → First operation wins, second receives "already cleared" message.

## Requirements *(mandatory)*

### Functional Requirements

**View History:**
- **FR-001**: System MUST provide an admin endpoint to list all SQL generations for a specific account-plugin combination, ordered by creation date descending.
- **FR-002**: System MUST return generation metadata including: generation ID, source batch ID, comparison batch ID (nullable), creation timestamp, statement counts (insert/update/delete), file size in bytes, generation duration in milliseconds, and site information.
- **FR-003**: System MUST provide an admin endpoint to retrieve SQL content for a specific generation with paginated inline preview (100 statements per page with syntax highlighting) and a download option for the full SQL file.
- **FR-004**: System MUST indicate whether a generation was an "initial load" (first batch with no comparison) or an "incremental update".

**Clear History:**
- **FR-005**: System MUST provide an admin endpoint to clear all plugin data for a specific account, accepting account ID and plugin ID as parameters.
- **FR-006**: System MUST delete all `plugin_sql_generations` records for the specified account-plugin when clearing history.
- **FR-007**: System MUST delete all S3 SQL files associated with the deleted generation records.
- **FR-008**: System MUST deactivate (soft delete) the `account_plugins` record when clearing history, invalidating any existing API keys.
- **FR-009**: System MUST create an audit log entry of type "PLUGIN_HISTORY_CLEARED" with metadata including counts of deleted records and files.
- **FR-010**: System MUST require explicit confirmation for the clear operation (confirmation token or two-step process).
- **FR-011**: System MUST validate that no batches are currently in "PROCESSING" status for the account before allowing clear.

**Regenerate:**
- **FR-012**: System MUST provide an admin endpoint to trigger SQL regeneration for a specific batch ID.
- **FR-013**: System MUST preserve the original generation record when regenerating, marking it as "superseded" rather than deleting.
- **FR-014**: System MUST create a new `plugin_sql_generations` record for the regenerated SQL with a reference to the superseded record.
- **FR-015**: System MUST validate that source batch CSV files still exist in S3 before attempting regeneration.
- **FR-016**: System MUST create an audit log entry of type "SQL_REGENERATION_COMPLETED" or "SQL_REGENERATION_FAILED".

**Security & Authorization:**
- **FR-017**: All endpoints MUST require ROLE_ADMIN authorization.
- **FR-018**: All operations MUST be logged in the plugin audit log with actor information (admin user ID).

### Key Entities

- **PluginSqlGeneration**: Represents a single SQL file generation event. Key attributes: unique ID, link to account-plugin, source batch, optional comparison batch, S3 key, statistics (counts, size, duration), creation timestamp, superseded flag (new), superseded_by reference (new).

- **AccountPlugin**: Represents the connection between an account and a plugin. Key attributes: account ID, plugin ID, activation status, plugin-specific data, API key hash.

- **PluginAuditLog**: Append-only log of plugin operations. Key attributes: timestamp, plugin ID, account ID, action type, success flag, metadata (JSONB for contextual data).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Administrators can view complete SQL generation history for any account within 2 seconds of page load.
- **SC-002**: Clear operation successfully removes 100% of associated database records and S3 files for a typical account (< 1000 generations) within 30 seconds.
- **SC-003**: Regeneration of a single batch completes within the same time bounds as original generation (typically < 60 seconds for files under 50MB).
- **SC-004**: All clear and regenerate operations are fully auditable with complete metadata preserved for 12 months.
- **SC-005**: Zero orphaned S3 files remain after clear operation (verified by comparing generation records count to S3 object count).
- **SC-006**: 100% of admin actions on plugin history are logged with actor identification.

## Assumptions

- Audit logs (`plugin_audit_logs`) are append-only and should never be deleted as part of "clear history" - only generation data and plugin connection are cleared.
- S3 file deletion uses best-effort approach with retry logic; partial failures are logged but don't block the overall operation.
- The existing partitioned table structure for audit logs handles long-term retention; this feature adds new action types but doesn't change retention policy.
- File size threshold for inline vs download response is 1MB (standard web practice).
- Administrators accessing these features have already been authenticated via Auth0 and have ROLE_ADMIN.

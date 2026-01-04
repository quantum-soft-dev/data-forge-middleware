# Feature Specification: BitBi Plugin Reinit Option

**Feature Branch**: `015-plugin-reinit`
**Created**: 2026-01-04
**Status**: Draft
**Input**: User description: "Bitbi plugin reinit option - add initialization on activation and reinit endpoint"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Initialize SQL on Plugin Activation (Priority: P1)

As an account owner activating the BitBi plugin for the first time (or reactivating after deactivation), I want the system to automatically generate SQL changes from my most recent completed batch, so that I can immediately start using the plugin without waiting for the next batch upload.

**Why this priority**: This is the core value proposition - users expect the plugin to be immediately useful upon activation. Without this, new users must wait for their next data upload before seeing any SQL output, creating a poor first experience and potential confusion about whether the plugin is working.

**Independent Test**: Can be fully tested by activating the plugin on an account that has existing completed batches, then verifying SQL changes are available via the plugin API.

**Acceptance Scenarios**:

1. **Given** an account with 3 completed batches and no active BitBi plugin, **When** the user activates the BitBi plugin, **Then** SQL generation runs for the most recent completed batch and SQL changes become available via the API.

2. **Given** an account with no completed batches, **When** the user activates the BitBi plugin, **Then** the plugin activates successfully without SQL generation (normal behavior - will generate on first batch completion).

3. **Given** an account with a deactivated BitBi plugin and new batches completed since deactivation, **When** the user reactivates the plugin, **Then** SQL generation runs for the most recent completed batch.

4. **Given** an account updating an already-active plugin (changing config), **When** the update completes, **Then** no SQL regeneration occurs (only new activations/reactivations trigger initialization).

---

### User Story 2 - Manual Plugin Reinitialization (Priority: P2)

As an account owner with an active BitBi plugin, I want to reinitialize the plugin's SQL state without changing my API key or plugin configuration, so that I can start fresh when my data has drifted or I need to rebuild the SQL history from the latest batch.

**Why this priority**: This enables recovery from data drift scenarios and provides a "soft reset" capability without requiring full deactivation (which would invalidate the API key and require downstream systems to be reconfigured).

**Independent Test**: Can be fully tested by calling the reinit endpoint on an account with existing SQL generations, verifying all previous SQL changes are deleted, and confirming new SQL is generated from the latest batch.

**Acceptance Scenarios**:

1. **Given** an active BitBi plugin with 10 existing SQL generation records, **When** the user calls the reinit endpoint, **Then** all existing SQL generation records and S3 files are deleted, new SQL is generated from the most recent completed batch, and the API key remains valid.

2. **Given** an active BitBi plugin with no completed batches, **When** the user calls the reinit endpoint, **Then** all existing SQL generation records are deleted (if any), no new SQL is generated, and the operation completes successfully.

3. **Given** a deactivated BitBi plugin, **When** the user calls the reinit endpoint, **Then** the request is rejected with an appropriate error indicating the plugin must be active.

4. **Given** an active BitBi plugin, **When** the user calls the reinit endpoint, **Then** an audit log entry is created recording the reinit action.

---

### Edge Cases

- What happens when SQL generation fails during activation? The plugin activation still succeeds (API key returned), but no SQL changes are available. User can retry via reinit endpoint or wait for next batch.
- What happens when reinit is called while a batch is currently being processed? The reinit should proceed - the in-progress batch will generate SQL after reinit completes, following normal event-driven flow.
- What happens when multiple sites have different "latest batches"? SQL generation processes the most recent completed batch per site, maintaining site isolation.
- What happens when the latest batch has already had SQL generated? During reinit, old generations are deleted first, then new generation runs (no idempotency conflict). During activation, if SQL already exists for the latest batch (from a previous session), skip regeneration.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST trigger SQL generation for the most recent completed batch when the BitBi plugin is newly activated (first-time activation).
- **FR-002**: System MUST trigger SQL generation for the most recent completed batch when the BitBi plugin is reactivated (was previously deactivated).
- **FR-003**: System MUST NOT trigger SQL generation when an already-active plugin's configuration is updated.
- **FR-004**: System MUST provide an endpoint allowing users to reinitialize their BitBi plugin SQL state.
- **FR-005**: The reinit operation MUST delete all existing SQL generation records for the account-plugin combination.
- **FR-006**: The reinit operation MUST delete all associated S3 SQL files for the account-plugin combination.
- **FR-007**: The reinit operation MUST generate new SQL from the most recent completed batch after clearing history.
- **FR-008**: The reinit operation MUST preserve the existing API key and plugin configuration.
- **FR-009**: The reinit operation MUST reject requests for inactive plugins with an appropriate error.
- **FR-010**: System MUST log an audit entry for reinit operations including action type, success/failure status, and relevant metadata.
- **FR-011**: If no completed batches exist, activation and reinit MUST complete successfully without generating SQL.
- **FR-012**: SQL generation during activation MUST run asynchronously to avoid blocking the activation response.
- **FR-013**: The reinit endpoint MUST be protected by the same authentication as other account plugin endpoints (OAuth2 with account context).

### Key Entities

- **AccountPlugin**: Represents the plugin integration for an account. Extended behavior: triggers SQL initialization on new activation or reactivation.
- **PluginSqlGeneration**: Represents a single SQL generation record. Affected by reinit (bulk deletion).
- **PluginAuditLog**: Records plugin actions. New action type: `REINIT` for tracking reinitialization operations.
- **Batch**: Represents an upload session. Used to find the "most recent completed batch" for initialization.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users activating the BitBi plugin on accounts with existing batches see SQL changes available within 60 seconds of activation.
- **SC-002**: Users can reinitialize their plugin and see fresh SQL changes within 60 seconds of calling the reinit endpoint.
- **SC-003**: 100% of reinit operations result in complete removal of previous SQL data before new generation begins.
- **SC-004**: Plugin API key remains valid and functional after reinit operation (no downstream system reconfiguration required).
- **SC-005**: All reinit operations are recorded in the audit log and visible to users in the plugin logs view.

## Assumptions

- The "most recent completed batch" is determined by the batch completion timestamp, selecting the single most recently completed batch across all sites for the account.
- SQL generation during activation follows the same comparison logic as event-driven generation (compares to previous batch if available, INSERT-only if first batch).
- The reinit endpoint follows RESTful conventions and is idempotent (calling it multiple times has the same effect as calling once).
- Concurrent reinit requests from the same user are handled gracefully (second request waits or fails gracefully).
- The reinit operation does not affect other plugins or plugin types - it is specific to the BitBi plugin.

## Out of Scope

- Changing the API key rotation behavior (API key is only regenerated on full deactivation/reactivation cycle).
- Adding selective reinitialization (e.g., reinit for specific sites only) - this is full account-plugin reinit.
- Adding scheduled/automatic reinitialization triggers.
- Modifying the SQL generation algorithm or comparison logic.

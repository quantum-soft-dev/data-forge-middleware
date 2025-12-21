# Feature Specification: Plugin System & Bit BI OAuth Integration

**Feature Branch**: `013-plugin-system`
**Created**: 2025-12-16
**Status**: Draft
**Input**: User description: "PRD-013: Extensible plugin system for Data Forge Middleware with Bit BI as the first OAuth-based integration plugin"

## Clarifications

### Session 2025-12-16

- Q: What retention period for plugin audit logs? → A: Indefinite retention (aligned with existing admin_action_logs and error_logs patterns; retention policy to be defined project-wide later)
- Q: What timeout for plugin.execute() calls? → A: 30 seconds (standard async task timeout)
- Q: Can deactivated plugins be reactivated? → A: Yes, via activation endpoint (upsert sets is_active=true)

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Connect DFM Account to Bit BI (Priority: P1)

A Bit BI user who also has a DFM account wants to connect their DFM account to Bit BI so that Bit BI can receive notifications when their batch uploads complete. The user initiates the connection from Bit BI, logs in with their DFM credentials via Auth0, and authorizes Bit BI to access their batch completion events.

**Why this priority**: This is the primary business value - enabling the Bit BI integration that drives the entire plugin system requirement. Without this working end-to-end, the plugin framework has no demonstrated value.

**Independent Test**: Can be fully tested by completing the OAuth flow from Bit BI and verifying the account-plugin link is created. Delivers immediate integration value to Bit BI users.

**Acceptance Scenarios**:

1. **Given** a user with an active DFM account, **When** they click "Connect to DFM" in Bit BI and complete Auth0 login, **Then** they are redirected back to Bit BI with a success confirmation and the plugin activation is recorded.

2. **Given** a user with an already connected account, **When** they attempt to connect again from Bit BI, **Then** the existing connection is updated with new tenant information (upsert behavior).

3. **Given** a user without a DFM account, **When** they attempt the OAuth flow, **Then** they see an appropriate error after Auth0 login indicating no DFM account exists.

4. **Given** a connected user, **When** their batch upload completes in DFM, **Then** the Bit BI plugin receives the batch completion event with relevant metadata.

---

### User Story 2 - Activate a Plugin for an Account (Priority: P1)

A third-party application (like Bit BI) that has completed OAuth authentication needs to activate a plugin for a DFM user's account. The application calls the activation endpoint with plugin-specific data, and DFM creates or updates the plugin activation record.

**Why this priority**: This is the foundational API that enables all plugin integrations. Without plugin activation, no plugin can function.

**Independent Test**: Can be tested by calling the activation endpoint with a valid OAuth token and verifying the database record is created with correct plugin data.

**Acceptance Scenarios**:

1. **Given** a valid OAuth token with required scopes and accountId claim, **When** the application calls POST /api/v1/plugins/{pluginId}/activate with valid plugin data, **Then** an account_plugins record is created and a success response is returned.

2. **Given** an already activated plugin for an account, **When** activation is called again with new data, **Then** the existing record is updated (not duplicated) and the updated timestamp reflects the change.

3. **Given** an OAuth token missing the accountId claim, **When** activation is attempted, **Then** a 403 Forbidden error is returned with appropriate error message.

4. **Given** plugin data that doesn't match the plugin's schema, **When** activation is attempted, **Then** a 400 Validation Error is returned with details about the schema mismatch.

5. **Given** a previously deactivated plugin for an account, **When** activation is called again, **Then** the plugin is reactivated (is_active=true) and resumes receiving events.

---

### User Story 3 - Deactivate a Plugin Integration (Priority: P2)

A DFM user wants to revoke a third-party application's access to their data. They can deactivate the plugin, which prevents the plugin from receiving future events and marks the integration as inactive.

**Why this priority**: Important for user control and security, but secondary to establishing the integration in the first place.

**Independent Test**: Can be tested by deactivating an active plugin and verifying subsequent API calls are rejected and events are not delivered.

**Acceptance Scenarios**:

1. **Given** an active plugin integration, **When** deactivation is called via DELETE /api/v1/plugins/{pluginId}/deactivate, **Then** the plugin record is marked inactive (is_active=false) and deactivated_at is set.

2. **Given** a deactivated plugin, **When** the third-party application attempts subsequent API calls, **Then** they receive 403 Forbidden responses.

3. **Given** a deactivated plugin, **When** a batch completion event occurs for that account, **Then** the plugin does NOT receive the event.

---

### User Story 4 - View Active Plugin Integrations (Priority: P2)

A DFM user wants to see which third-party applications have access to their account data. They can view a list of all active plugin integrations including when they were activated and last used.

**Why this priority**: Provides transparency and control to users, supporting security best practices. Secondary to core integration functionality.

**Independent Test**: Can be tested by activating plugins and then retrieving the list to verify accurate display of integration metadata.

**Acceptance Scenarios**:

1. **Given** a user with one or more active plugin integrations, **When** they request GET /api/v1/account/plugins, **Then** they see a paginated list showing plugin name, activation date, and last used date.

2. **Given** a user with active plugins, **When** viewing the list, **Then** plugin-specific data (like tenantId) is NOT exposed for security.

3. **Given** a user with no active plugins, **When** they request the plugin list, **Then** they see an empty list with appropriate messaging.

---

### User Story 5 - Receive Batch Completion Notifications (Priority: P2)

An activated plugin (like Bit BI) automatically receives notifications when batch processing completes for accounts that have activated the plugin. The notification includes batch metadata enabling the third-party to take appropriate action.

**Why this priority**: Core value proposition for integrations, but dependent on activation working first.

**Independent Test**: Can be tested by completing a batch upload and verifying the plugin's execute method is called with correct event data.

**Acceptance Scenarios**:

1. **Given** an account with the Bit BI plugin activated, **When** a batch upload completes successfully, **Then** the Bit BI plugin receives a BATCH_COMPLETED event with batchId, accountId, and batch metadata.

2. **Given** an account with the Bit BI plugin activated, **When** a batch fails or times out, **Then** appropriate failure events are dispatched to the plugin.

3. **Given** multiple accounts with the same plugin activated, **When** batch events occur, **Then** each account's plugin activation receives events only for its own batches.

---

### User Story 6 - Admin Views Plugin Audit Trail (Priority: P3)

A DFM administrator needs to monitor plugin usage and troubleshoot issues. They can view an audit log of all plugin API calls and event executions to understand usage patterns and diagnose problems.

**Why this priority**: Important for operations and compliance but not required for core functionality.

**Independent Test**: Can be tested by performing plugin operations and verifying they appear in the audit log with correct metadata.

**Acceptance Scenarios**:

1. **Given** plugin API calls and event executions have occurred, **When** an admin queries the audit log, **Then** they see timestamped entries with pluginId, accountId, action type, and success/failure status.

2. **Given** audit entries contain sensitive data, **When** viewing the audit log, **Then** request body content is stored as a hash (not plaintext) for privacy.

3. **Given** large audit history, **When** querying the log, **Then** results are filterable by account, plugin, action type, and date range.

---

### Edge Cases

- What happens when a plugin's execute method throws an exception? (Answer: Error is logged but not propagated; other plugins and core system continue functioning)
- What happens when Auth0 is unavailable during plugin activation? (Answer: Fail fast with 503 Service Unavailable)
- What happens when a plugin is registered but not enabled in plugin_configs? (Answer: Activation attempts return 404 Plugin Not Found)
- What happens when an account is deactivated that has active plugins? (Answer: Plugin integrations should remain but become effectively inactive since the account is inactive)
- What happens when duplicate activation calls occur concurrently? (Answer: Database unique constraint ensures only one record per account-plugin pair; upsert handles gracefully)

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide a plugin registration mechanism where plugins are discovered and loaded at application startup
- **FR-002**: System MUST store plugin configuration (client_id, client_name, enabled status, plugin-specific config) in a database table
- **FR-003**: System MUST allow third-party applications to activate plugins for user accounts via authenticated API endpoint
- **FR-004**: System MUST validate plugin-specific data against the plugin's defined schema before storing
- **FR-005**: System MUST support upsert behavior for plugin activation (create new, update existing, or reactivate deactivated plugin by setting is_active=true)
- **FR-006**: System MUST call plugin lifecycle hooks (onActivate, onDeactivate) when activation state changes
- **FR-007**: System MUST dispatch events (BATCH_COMPLETED, etc.) asynchronously to all subscribed plugins for the relevant account
- **FR-008**: System MUST isolate plugin execution failures so they don't affect other plugins or core system operations; plugin.execute() calls MUST timeout after 30 seconds
- **FR-009**: System MUST allow users to deactivate plugin integrations, revoking future access
- **FR-010**: System MUST reject API calls from deactivated plugins with 403 Forbidden
- **FR-011**: System MUST provide an endpoint for users to view their active plugin integrations
- **FR-012**: System MUST NOT expose plugin-specific data (tenantId, etc.) in list responses for security
- **FR-013**: System MUST log all plugin API calls and event executions for audit purposes
- **FR-014**: System MUST store audit log request bodies as hashed values (not plaintext) for privacy
- **FR-015**: System MUST validate OAuth tokens for plugin endpoints, requiring appropriate scopes and accountId claim
- **FR-016**: System MUST support the Bit BI plugin with BATCH_COMPLETED event subscription
- **FR-017**: System MUST extract accountId and clientId from OAuth token custom claims for authorization
- **FR-018**: System MUST track last_used_at timestamp when plugins receive events or make API calls

### Key Entities

- **Plugin**: A registered extension module with unique identifier, name, version, required scopes, supported events, and data schema. Discovered at startup and registered in the plugin registry.

- **PluginConfig**: Database-stored configuration for each plugin including Auth0 client_id, display name, enabled status, and plugin-specific settings. Links registered plugins to their OAuth credentials.

- **AccountPlugin**: The activation record linking an Account to a Plugin. Contains plugin-specific data (JSONB), activation status, and timestamps. Unique constraint on (account_id, plugin_id) ensures one activation per combination.

- **PluginEvent**: An occurrence in the system (BATCH_COMPLETED, FILE_UPLOADED, etc.) that triggers plugin execution. Contains event type, account ID, resource ID, timestamp, and metadata.

- **PluginAuditLog**: Record of plugin API calls and event executions for monitoring and compliance. Partitioned by month for efficient querying; indefinite retention aligned with existing audit log patterns.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can complete the OAuth connection flow from Bit BI to DFM in under 30 seconds (excluding Auth0 login time)
- **SC-002**: Plugin activation operations complete in under 200ms (95th percentile)
- **SC-003**: Batch completion events are dispatched to subscribed plugins within 500ms of batch completion (95th percentile)
- **SC-004**: Plugin execution errors occur in less than 0.1% of event dispatches
- **SC-005**: All registered plugins are loaded and available within 100ms of application startup
- **SC-006**: Users can view and manage their plugin integrations without technical assistance
- **SC-007**: Administrators can identify and diagnose plugin issues using the audit log within 5 minutes
- **SC-008**: Deactivated plugins receive zero events or API access after deactivation

## Assumptions

- Auth0 is already configured and operational for DFM user authentication (documented in PRD-011)
- The accounts table exists and has the standard structure including id (UUID) primary key
- Bit BI will implement their side of the OAuth flow (client-side PKCE, token storage, callback handling)
- Batch completion events are already being raised by the BatchService (may need integration point)
- Plugin-specific webhook/notification delivery to third parties (e.g., HTTP calls to Bit BI) is out of scope for v1; plugins will only log events initially
- Refresh token rotation and management is handled by Auth0; DFM only validates access tokens
- The plugin system will initially support only compile-time plugins (no runtime dynamic loading)

## Out of Scope

- Dynamic plugin loading at runtime (plugins compile with the application)
- Plugin marketplace or self-service partner registration
- Integration API for reading batch/file data (separate feature)
- Webhook callbacks to third-party endpoints (future enhancement)
- User-facing UI for managing plugins (end-users activate/deactivate via API from third-party applications)
- Multiple authentication methods per plugin (OAuth2 only for v1)

## Admin UI Requirements (Added 2025-12-20)

The following Admin UI features have been implemented for monitoring and viewing plugin system data:

### Admin Plugin Dashboard

**Route**: `/admin/plugins` (requires ROLE_ADMIN)

**Features**:
1. **Registered Plugins View**:
   - Grid display of all registered plugins
   - Plugin status badge (Enabled/Disabled)
   - Plugin ID, display name, version
   - Supported events tags
   - Creation timestamp (relative)
   - Click to view plugin-specific audit logs

2. **Audit Logs View**:
   - Paginated table of all plugin operations
   - Filterable by: Plugin ID, Account ID, Action Type, Success/Failed, Date Range
   - Columns: Time, Plugin, Action, Status, Account, Duration, HTTP Status, Error, IP Address
   - Action type badges with semantic colors (ACTIVATE=green, DEACTIVATE=yellow, EVENT_FAILED=red, etc.)

### Frontend Implementation

**Architecture**: Feature-Sliced Design (FSD)

**Components**:
- `entities/plugin/` - Types, query keys, status badges
- `features/plugin-admin/` - API client, TanStack Query hooks, UI components
- `widgets/plugin-admin/` - Container widgets (PluginListWidget, AuditLogWidget)
- `pages/admin/plugins/` - PluginsAdminPage with tabs

**API Integration**:
- `GET /api/v1/admin/plugins` - List registered plugins
- `GET /api/v1/admin/plugins/audit` - Query audit logs with filters

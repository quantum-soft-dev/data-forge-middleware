/**
 * Centralized API path constants for the Data Forge Middleware frontend.
 *
 * This file mirrors the backend ApiRoutes.java to ensure path consistency
 * between frontend and backend. Using constants prevents path drift and
 * provides compile-time safety for API endpoint references.
 *
 * The API is structured into two main groups:
 * - Device API (/api/v1/device/*): For client devices using Custom JWT authentication
 * - UI/Admin API (/api/v1/*): For web interface using Auth0 OAuth2 authentication
 *
 * @see /src/main/java/com/bitbi/dfm/shared/api/ApiRoutes.java
 * @since 1.0.0
 */

// ==================== Device API ====================

/**
 * Base path for Device API endpoints.
 * All device-facing endpoints (IoT devices, mobile apps, data collection clients)
 * are under this base path and use Custom JWT authentication.
 *
 * The frontend is not a device client: it only serves the browser half of the Device
 * Authorization Flow (DEVICE_AUTHORIZATION_VERIFY below). The batch, file, error and token
 * constants that used to live here were never referenced by any component — devices call those
 * endpoints directly — so they were removed rather than left to drift out of sync with the backend.
 */
export const DEVICE_API_BASE = '/v1/device';

// ==================== UI/Admin API ====================

/**
 * Base path for UI/Admin API endpoints.
 * All admin and user-facing endpoints (web interface, admin dashboard)
 * are under this base path and use Auth0 OAuth2 authentication.
 */
export const ADMIN_API_BASE = '/v1';

// Accounts
export const ACCOUNTS = `${ADMIN_API_BASE}/accounts`;
export const ACCOUNTS_ID = (id: string) => `${ACCOUNTS}/${id}`;
export const ACCOUNTS_LOCK = (id: string) => `${ACCOUNTS}/${id}/lock`;
export const ACCOUNTS_UNLOCK = (id: string) => `${ACCOUNTS}/${id}/unlock`;
export const ACCOUNTS_RESET_PASSWORD = (id: string) => `${ACCOUNTS}/${id}/reset-password`;
export const ACCOUNTS_AUDIT_LOGS = (id: string) => `${ACCOUNTS}/${id}/audit-logs`;

// Sites (User-facing)
export const SITES_USER = `${ADMIN_API_BASE}/account/sites`;
export const SITES_USER_ID = (id: string) => `${SITES_USER}/${id}`;
export const SITES_USER_ACTIVATE = (id: string) => `${SITES_USER}/${id}/activate`;
export const SITES_USER_DEACTIVATE = (id: string) => `${SITES_USER}/${id}/deactivate`;

// Sites (Admin)
export const SITES = `${ADMIN_API_BASE}/sites`;
export const SITES_ID = (id: string) => `${SITES}/${id}`;
export const SITES_STATISTICS = (id: string) => `${SITES}/${id}/statistics`;
export const SITES_RETENTION = (id: string) => `${SITES}/${id}/retention`;
export const SITES_BY_ACCOUNT = (accountId: string) => `${ACCOUNTS}/${accountId}/sites`;
export const SITES_CREATE = (accountId: string) => `${ACCOUNTS}/${accountId}/sites`;
export const SITES_ACTIVATE = (accountId: string, siteId: string) => `${ACCOUNTS}/${accountId}/sites/${siteId}/activate`;
export const SITES_DEACTIVATE = (accountId: string, siteId: string) => `${ACCOUNTS}/${accountId}/sites/${siteId}/deactivate`;
export const SITES_DELETE_BY_ACCOUNT = (accountId: string, siteId: string) => `${ACCOUNTS}/${accountId}/sites/${siteId}`;

// Batches (Admin)
export const BATCHES_ADMIN = `${ADMIN_API_BASE}/batches`;
export const BATCHES_ADMIN_ID = (id: string) => `${BATCHES_ADMIN}/${id}`;
export const BATCHES_ADMIN_CLEANUP = `${BATCHES_ADMIN}/cleanup`;

// History (User-facing)
export const HISTORY = `${ADMIN_API_BASE}/history`;
export const HISTORY_BATCHES = `${HISTORY}/batches`;
export const HISTORY_BATCH_ID = (batchId: string) => `${HISTORY_BATCHES}/${batchId}`;
export const HISTORY_FILE_DOWNLOAD = (batchId: string, fileId: string) => `${HISTORY_BATCHES}/${batchId}/files/${fileId}/download`;
export const HISTORY_ZIP_DOWNLOAD = (batchId: string) => `${HISTORY_BATCHES}/${batchId}/download-zip`;
export const HISTORY_EXCEL_EXPORT = (batchId: string) => `${HISTORY_BATCHES}/${batchId}/export-excel`;
export const HISTORY_ERRORS = (batchId: string) => `${HISTORY_BATCHES}/${batchId}/errors`;

// Errors (Admin)
export const ERRORS_ADMIN = `${ADMIN_API_BASE}/errors`;
export const ERRORS_EXPORT = `${ERRORS_ADMIN}/export`;

// Comparisons
export const COMPARISONS = `${ADMIN_API_BASE}/comparisons`;
export const COMPARISONS_ID = (id: number) => `${COMPARISONS}/${id}`;
export const COMPARISONS_RESULTS = (id: number) => `${COMPARISONS}/${id}/results`;
export const COMPARISONS_SUMMARY = (id: number) => `${COMPARISONS}/${id}/summary`;
export const COMPARISONS_BY_BATCH = (batchId: string) => `${COMPARISONS}/by-batch/${batchId}`;
export const COMPARISONS_DOWNLOAD = (id: number) => `${COMPARISONS}/${id}/download`;
export const COMPARISONS_SUMMARY_DOWNLOAD = (id: number) => `${COMPARISONS}/${id}/summary/download`;

// Plugin Administration (Admin)
export const ADMIN_PLUGINS = `${ADMIN_API_BASE}/admin/plugins`;
export const ADMIN_PLUGINS_AUDIT = `${ADMIN_PLUGINS}/audit`;
export const ADMIN_PLUGINS_AUDIT_BY_PLUGIN = (pluginId: string) => `${ADMIN_PLUGINS}/${pluginId}/audit`;

// Admin Settings
export const ADMIN_SETTINGS = `${ADMIN_API_BASE}/admin/settings`;
export const SETTINGS_BATCH_RETENTION_SCHEDULE = `${ADMIN_SETTINGS}/batch-retention-schedule`;

// Account Plugins (User-facing)
export const ACCOUNT_PLUGINS = `${ADMIN_API_BASE}/account/plugins`;
export const ACCOUNT_PLUGIN_LOGS = (pluginId: string) => `${ACCOUNT_PLUGINS}/${pluginId}/logs`;

// Account Plugin secret rotation (User-facing, owner OAuth2; new secret shown once)
export const ACCOUNT_PLUGIN_ROTATE_API_KEY = `${ACCOUNT_PLUGINS}/bit-bi/rotate-api-key`;
export const ACCOUNT_PLUGIN_ROTATE_PASSWORD = `${ACCOUNT_PLUGINS}/parquet-export/rotate-password`;

// Account Plugin Batch SQL Management (User-facing)
export const ACCOUNT_PLUGIN_BATCHES = (pluginId: string) =>
  `${ACCOUNT_PLUGINS}/${pluginId}/batches`;
export const ACCOUNT_PLUGIN_GENERATE_SQL = (pluginId: string) =>
  `${ACCOUNT_PLUGINS}/${pluginId}/generate-sql`;
export const ACCOUNT_PLUGIN_GENERATIONS = (pluginId: string) =>
  `${ACCOUNT_PLUGINS}/${pluginId}/generations`;
export const ACCOUNT_PLUGIN_GENERATION = (pluginId: string, generationId: string) =>
  `${ACCOUNT_PLUGIN_GENERATIONS(pluginId)}/${generationId}`;
export const ACCOUNT_PLUGIN_GENERATION_CONTENT = (pluginId: string, generationId: string) =>
  `${ACCOUNT_PLUGIN_GENERATION(pluginId, generationId)}/content`;
export const ACCOUNT_PLUGIN_GENERATION_DOWNLOAD = (pluginId: string, generationId: string) =>
  `${ACCOUNT_PLUGIN_GENERATION(pluginId, generationId)}/download`;
export const ACCOUNT_PLUGIN_GENERATION_REGENERATE = (pluginId: string, generationId: string) =>
  `${ACCOUNT_PLUGIN_GENERATION(pluginId, generationId)}/regenerate`;

// Plugin Activation
export const PLUGINS = `${ADMIN_API_BASE}/plugins`;
export const PLUGINS_ACTIVATE = (pluginId: string) => `${PLUGINS}/${pluginId}/activate`;
export const PLUGINS_DEACTIVATE = (pluginId: string) => `${PLUGINS}/${pluginId}/deactivate`;

// Plugin Account-Plugins (Admin) - for SQL History tab
export const ADMIN_PLUGIN_ACCOUNT_PLUGINS = (pluginId: string) =>
  `${ADMIN_PLUGINS}/${pluginId}/account-plugins`;

// Plugin History (Admin)
export const ADMIN_PLUGIN_GENERATIONS = (pluginId: string, accountId: string) =>
  `${ADMIN_PLUGINS}/${pluginId}/accounts/${accountId}/generations`;
export const ADMIN_PLUGIN_GENERATION = (pluginId: string, accountId: string, generationId: string) =>
  `${ADMIN_PLUGIN_GENERATIONS(pluginId, accountId)}/${generationId}`;
export const ADMIN_PLUGIN_GENERATION_CONTENT = (pluginId: string, accountId: string, generationId: string) =>
  `${ADMIN_PLUGIN_GENERATION(pluginId, accountId, generationId)}/content`;
export const ADMIN_PLUGIN_GENERATION_DOWNLOAD = (pluginId: string, accountId: string, generationId: string) =>
  `${ADMIN_PLUGIN_GENERATION(pluginId, accountId, generationId)}/download`;
export const ADMIN_PLUGIN_GENERATION_REGENERATE = (pluginId: string, accountId: string, generationId: string) =>
  `${ADMIN_PLUGIN_GENERATION(pluginId, accountId, generationId)}/regenerate`;
export const ADMIN_PLUGIN_HISTORY = (pluginId: string, accountId: string) =>
  `${ADMIN_PLUGINS}/${pluginId}/accounts/${accountId}/history`;
export const ADMIN_PLUGIN_HISTORY_SUMMARY = (pluginId: string, accountId: string) =>
  `${ADMIN_PLUGIN_HISTORY(pluginId, accountId)}/summary`;

// ==================== Device Authorization (RFC 8628) ====================

/**
 * Device Authorization endpoints for Device Code Flow.
 * Allows headless devices to authorize through browser.
 *
 * Flow:
 * 1. Device calls /authorize (public) → receives deviceCode, userCode
 * 2. User opens /verify?code=XXX → sees site info, approves/denies
 * 3. On approve → site is created automatically
 * 4. Device polls /token (public) → receives credentials when approved
 */
// Only /verify is a browser-side route; /authorize and /token are called by the device itself.
export const DEVICE_AUTHORIZATION_VERIFY = `${DEVICE_API_BASE}/verify`;

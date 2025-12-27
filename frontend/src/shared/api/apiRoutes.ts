/**
 * Centralized API path constants for the Data Forge Middleware frontend.
 *
 * This file mirrors the backend ApiRoutes.java to ensure path consistency
 * between frontend and backend. Using constants prevents path drift and
 * provides compile-time safety for API endpoint references.
 *
 * The API is structured into two main groups:
 * - Device API (/api/v1/device/*): For client devices using Custom JWT authentication
 * - UI/Admin API (/api/v1/*): For web interface using Keycloak OAuth2 authentication
 *
 * @see /src/main/java/com/bitbi/dfm/shared/api/ApiRoutes.java
 * @since 1.0.0
 */

// ==================== Device API ====================

/**
 * Base path for Device API endpoints.
 * All device-facing endpoints (IoT devices, mobile apps, data collection clients)
 * are under this base path and use Custom JWT authentication.
 */
export const DEVICE_API_BASE = '/v1/device';

// Device Authentication
export const DEVICE_AUTH = `${DEVICE_API_BASE}/auth`;
export const DEVICE_AUTH_TOKEN = `${DEVICE_AUTH}/token`;

// Device Batches
export const DEVICE_BATCHES = `${DEVICE_API_BASE}/batches`;
export const DEVICE_BATCHES_START = `${DEVICE_BATCHES}/start`;
export const DEVICE_BATCHES_COMPLETE = (id: string) => `${DEVICE_BATCHES}/${id}/complete`;
export const DEVICE_BATCHES_FAIL = (id: string) => `${DEVICE_BATCHES}/${id}/fail`;
export const DEVICE_BATCHES_CANCEL = (id: string) => `${DEVICE_BATCHES}/${id}/cancel`;
export const DEVICE_BATCHES_GET = (id: string) => `${DEVICE_BATCHES}/${id}`;

// Device Files
export const DEVICE_FILES = `${DEVICE_API_BASE}/files`;
export const DEVICE_FILES_UPLOAD = (batchId: string) => `${DEVICE_FILES}/batches/${batchId}/upload`;
export const DEVICE_FILES_GET = (batchId: string, fileId: string) => `${DEVICE_FILES}/batches/${batchId}/files/${fileId}`;

// Device Errors
export const DEVICE_ERRORS = `${DEVICE_API_BASE}/errors`;
export const DEVICE_ERRORS_LOG = DEVICE_ERRORS;
export const DEVICE_ERRORS_LOG_BATCH = (batchId: string) => `${DEVICE_ERRORS}/batches/${batchId}`;
export const DEVICE_ERRORS_GET = (errorId: string) => `${DEVICE_ERRORS}/${errorId}`;

// ==================== UI/Admin API ====================

/**
 * Base path for UI/Admin API endpoints.
 * All admin and user-facing endpoints (web interface, admin dashboard)
 * are under this base path and use Keycloak OAuth2 authentication.
 */
export const ADMIN_API_BASE = '/v1';

// Accounts
export const ACCOUNTS = `${ADMIN_API_BASE}/accounts`;
export const ACCOUNTS_WITH_KEYCLOAK = `${ACCOUNTS}/with-keycloak`;
export const ACCOUNTS_ID = (id: string) => `${ACCOUNTS}/${id}`;
export const ACCOUNTS_LOCK = (id: string) => `${ACCOUNTS}/${id}/lock`;
export const ACCOUNTS_UNLOCK = (id: string) => `${ACCOUNTS}/${id}/unlock`;
export const ACCOUNTS_RESET_PASSWORD = (id: string) => `${ACCOUNTS}/${id}/reset-password`;
export const ACCOUNTS_AUDIT_LOGS = (id: string) => `${ACCOUNTS}/${id}/audit-logs`;
export const ACCOUNTS_WITH_KEYCLOAK_ID = (id: string) => `${ACCOUNTS}/${id}/with-keycloak`;

// Sites (User-facing)
export const SITES_USER = `${ADMIN_API_BASE}/account/sites`;
export const SITES_USER_ID = (id: string) => `${SITES_USER}/${id}`;
export const SITES_USER_ACTIVATE = (id: string) => `${SITES_USER}/${id}/activate`;
export const SITES_USER_DEACTIVATE = (id: string) => `${SITES_USER}/${id}/deactivate`;

// Sites (Admin)
export const SITES = `${ADMIN_API_BASE}/sites`;
export const SITES_ID = (id: string) => `${SITES}/${id}`;
export const SITES_STATISTICS = (id: string) => `${SITES}/${id}/statistics`;
export const SITES_BY_ACCOUNT = (accountId: string) => `${ACCOUNTS}/${accountId}/sites`;
export const SITES_CREATE = (accountId: string) => `${ACCOUNTS}/${accountId}/sites`;
export const SITES_ACTIVATE = (accountId: string, siteId: string) => `${ACCOUNTS}/${accountId}/sites/${siteId}/activate`;
export const SITES_DEACTIVATE = (accountId: string, siteId: string) => `${ACCOUNTS}/${accountId}/sites/${siteId}/deactivate`;
export const SITES_DELETE_BY_ACCOUNT = (accountId: string, siteId: string) => `${ACCOUNTS}/${accountId}/sites/${siteId}`;

// Batches (Admin)
export const BATCHES_ADMIN = `${ADMIN_API_BASE}/batches`;
export const BATCHES_ADMIN_ID = (id: string) => `${BATCHES_ADMIN}/${id}`;

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

// Account Plugins (User-facing)
export const ACCOUNT_PLUGINS = `${ADMIN_API_BASE}/account/plugins`;
export const ACCOUNT_PLUGIN_LOGS = (pluginId: string) => `${ACCOUNT_PLUGINS}/${pluginId}/logs`;

// Plugin Activation
export const PLUGINS = `${ADMIN_API_BASE}/plugins`;
export const PLUGINS_ACTIVATE = (pluginId: string) => `${PLUGINS}/${pluginId}/activate`;
export const PLUGINS_DEACTIVATE = (pluginId: string) => `${PLUGINS}/${pluginId}/deactivate`;

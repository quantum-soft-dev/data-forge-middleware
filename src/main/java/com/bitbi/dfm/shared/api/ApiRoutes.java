package com.bitbi.dfm.shared.api;

/**
 * Centralized API path constants for the Data Forge Middleware.
 * <p>
 * This class defines all API endpoint paths used throughout the application.
 * Using constants ensures compile-time safety, prevents path drift between
 * controllers and tests, and provides a single source of truth for API routes.
 * </p>
 * <p>
 * The API is structured into two main groups:
 * </p>
 * <ul>
 *   <li><b>Device API</b> ({@code /api/v1/device/*}): For client devices using Custom JWT authentication</li>
 *   <li><b>UI/Admin API</b> ({@code /api/v1/*}): For web interface using Auth0 OAuth2 authentication</li>
 * </ul>
 *
 * @since 1.0.0
 * @see <a href="specs/010-api-unification-goal/spec.md">API Unification Specification</a>
 */
public final class ApiRoutes {

    // ==================== Device API ====================

    /**
     * Base path for Device API endpoints.
     * <p>
     * All device-facing endpoints (for IoT devices, mobile apps, data collection clients)
     * are under this base path and use Custom JWT authentication.
     * </p>
     */
    public static final String DEVICE_API_BASE = "/api/v1/device";

    // Device Authentication
    /**
     * Base path for device authentication endpoints.
     */
    public static final String DEVICE_AUTH = DEVICE_API_BASE + "/auth";

    /**
     * Device token generation endpoint.
     * <p>
     * Method: POST<br>
     * Authentication: Basic Auth (site domain + client secret)<br>
     * Returns: JWT token for subsequent Device API calls
     * </p>
     */
    public static final String DEVICE_AUTH_TOKEN = DEVICE_AUTH + "/token";

    // Device Batches
    /**
     * Base path for device batch management endpoints.
     */
    public static final String DEVICE_BATCHES = DEVICE_API_BASE + "/batches";

    /**
     * Start new batch endpoint.
     * <p>
     * Method: POST<br>
     * Authentication: Custom JWT<br>
     * Returns: BatchResponseDto
     * </p>
     */
    public static final String DEVICE_BATCHES_START = DEVICE_BATCHES + "/start";

    /**
     * Complete batch endpoint.
     * <p>
     * Method: POST<br>
     * Path Variable: {id} - Batch ID<br>
     * Authentication: Custom JWT<br>
     * Returns: BatchResponseDto
     * </p>
     */
    public static final String DEVICE_BATCHES_COMPLETE = DEVICE_BATCHES + "/{id}/complete";

    /**
     * Complete batch with warnings endpoint.
     * <p>
     * Method: POST<br>
     * Path Variable: {id} - Batch ID<br>
     * Authentication: Custom JWT<br>
     * Returns: BatchResponseDto with COMPLETED_WITH_WARNINGS status
     * </p>
     */
    public static final String DEVICE_BATCHES_COMPLETE_WITH_WARNINGS = DEVICE_BATCHES + "/{id}/complete-with-warnings";

    /**
     * Mark batch as failed endpoint.
     * <p>
     * Method: POST<br>
     * Path Variable: {id} - Batch ID<br>
     * Authentication: Custom JWT<br>
     * Returns: BatchResponseDto
     * </p>
     */
    public static final String DEVICE_BATCHES_FAIL = DEVICE_BATCHES + "/{id}/fail";

    /**
     * Cancel batch endpoint.
     * <p>
     * Method: POST<br>
     * Path Variable: {id} - Batch ID<br>
     * Authentication: Custom JWT<br>
     * Returns: BatchResponseDto
     * </p>
     */
    public static final String DEVICE_BATCHES_CANCEL = DEVICE_BATCHES + "/{id}/cancel";

    /**
     * Get batch details endpoint.
     * <p>
     * Method: GET<br>
     * Path Variable: {id} - Batch ID<br>
     * Authentication: Custom JWT<br>
     * Returns: BatchResponseDto
     * </p>
     */
    public static final String DEVICE_BATCHES_GET = DEVICE_BATCHES + "/{id}";

    // Device Files
    /**
     * Base path for device file upload endpoints.
     */
    public static final String DEVICE_FILES = DEVICE_API_BASE + "/files";

    /**
     * Upload file to batch endpoint.
     * <p>
     * Method: POST<br>
     * Path Variable: {batchId} - Batch ID<br>
     * Authentication: Custom JWT<br>
     * Content-Type: multipart/form-data<br>
     * Returns: FileUploadResponseDto
     * </p>
     */
    public static final String DEVICE_FILES_UPLOAD = DEVICE_FILES + "/batches/{batchId}/upload";

    /**
     * Get file metadata endpoint.
     * <p>
     * Method: GET<br>
     * Path Variables: {batchId} - Batch ID, {fileId} - File ID<br>
     * Authentication: Custom JWT<br>
     * Returns: FileMetadataDto
     * </p>
     */
    public static final String DEVICE_FILES_GET = DEVICE_FILES + "/batches/{batchId}/files/{fileId}";

    // Device Errors
    /**
     * Base path for device error logging endpoints.
     */
    public static final String DEVICE_ERRORS = DEVICE_API_BASE + "/errors";

    /**
     * Log standalone error endpoint.
     * <p>
     * Method: POST<br>
     * Authentication: Custom JWT<br>
     * Returns: ErrorLogResponseDto
     * </p>
     */
    public static final String DEVICE_ERRORS_LOG = DEVICE_ERRORS;

    /**
     * Log batch-specific error endpoint.
     * <p>
     * Method: POST<br>
     * Path Variable: {batchId} - Batch ID<br>
     * Authentication: Custom JWT<br>
     * Returns: ErrorLogResponseDto
     * </p>
     */
    public static final String DEVICE_ERRORS_LOG_BATCH = DEVICE_ERRORS + "/batches/{batchId}";

    /**
     * Get error details endpoint.
     * <p>
     * Method: GET<br>
     * Path Variable: {errorId} - Error ID<br>
     * Authentication: Custom JWT<br>
     * Returns: ErrorLogResponseDto
     * </p>
     */
    public static final String DEVICE_ERRORS_GET = DEVICE_ERRORS + "/{errorId}";

    // ==================== UI/Admin API ====================

    /**
     * Base path for UI/Admin API endpoints.
     * <p>
     * All admin and user-facing endpoints (for web interface, admin dashboard)
     * are under this base path and use Auth0 OAuth2 authentication.
     * </p>
     */
    public static final String ADMIN_API_BASE = "/api/v1";

    // Accounts
    /**
     * Base path for account management endpoints.
     * <p>
     * Requires: ROLE_ADMIN (Auth0)
     * </p>
     */
    public static final String ACCOUNTS = ADMIN_API_BASE + "/accounts";

    /**
     * List accounts endpoint.
     * <p>
     * Method: GET<br>
     * Authentication: OAuth2 (ROLE_ADMIN)<br>
     * Query Params: search (optional), page (default=0), size (default=20)<br>
     * Returns: AccountPageDto (paginated list of AccountDetailDto with Auth0 fields)
     * </p>
     */
    public static final String ACCOUNTS_LIST = ACCOUNTS;

    /**
     * Create account endpoint.
     * <p>
     * Method: POST<br>
     * Authentication: OAuth2 (ROLE_ADMIN)<br>
     * Request Body: CreateAccountRequestDto<br>
     * Returns: AccountResponseDto with temporary password
     * </p>
     */
    public static final String ACCOUNTS_CREATE = ACCOUNTS;

    /**
     * Account by ID endpoint.
     * <p>
     * Methods: GET, PUT, DELETE<br>
     * Path Variable: {id} - Account ID<br>
     * Authentication: Auth0 OAuth2 (ROLE_ADMIN)
     * </p>
     */
    public static final String ACCOUNTS_ID = ACCOUNTS + "/{id}";

    /**
     * Lock account endpoint.
     * <p>
     * Method: POST<br>
     * Path Variable: {id} - Account ID<br>
     * Authentication: Auth0 OAuth2 (ROLE_ADMIN)<br>
     * Returns: AccountResponseDto
     * </p>
     */
    public static final String ACCOUNTS_LOCK = ACCOUNTS + "/{id}/lock";

    /**
     * Unlock account endpoint.
     * <p>
     * Method: POST<br>
     * Path Variable: {id} - Account ID<br>
     * Authentication: Auth0 OAuth2 (ROLE_ADMIN)<br>
     * Returns: AccountResponseDto
     * </p>
     */
    public static final String ACCOUNTS_UNLOCK = ACCOUNTS + "/{id}/unlock";

    /**
     * Reset account password endpoint.
     * <p>
     * Method: POST<br>
     * Path Variable: {id} - Account ID<br>
     * Authentication: Auth0 OAuth2 (ROLE_ADMIN)<br>
     * Returns: Temporary password
     * </p>
     */
    public static final String ACCOUNTS_RESET_PASSWORD = ACCOUNTS + "/{id}/reset-password";

    /**
     * Get account audit logs endpoint.
     * <p>
     * Method: GET<br>
     * Path Variable: {id} - Account ID<br>
     * Authentication: Auth0 OAuth2 (ROLE_ADMIN)<br>
     * Returns: List&lt;AdminActionLog&gt;
     * </p>
     */
    public static final String ACCOUNTS_AUDIT_LOGS = ACCOUNTS + "/{id}/audit-logs";

    // Sites
    /**
     * Base path for site management endpoints (Admin).
     * <p>
     * Requires: ROLE_ADMIN (Auth0)
     * </p>
     */
    public static final String SITES = ADMIN_API_BASE + "/sites";

    /**
     * Base path for user site management endpoints.
     * <p>
     * Requires: Authenticated user with Auth0 JWT containing accountId claim<br>
     * Users can only manage their own sites
     * </p>
     */
    public static final String SITES_USER = ADMIN_API_BASE + "/account/sites";

    /**
     * Site by ID endpoint.
     * <p>
     * Methods: GET, PUT, DELETE<br>
     * Path Variable: {id} - Site ID<br>
     * Authentication: Auth0 OAuth2 (ROLE_ADMIN)
     * </p>
     */
    public static final String SITES_ID = SITES + "/{id}";

    /**
     * Get site statistics endpoint.
     * <p>
     * Method: GET<br>
     * Path Variable: {id} - Site ID<br>
     * Authentication: Auth0 OAuth2 (ROLE_ADMIN)<br>
     * Returns: Site statistics
     * </p>
     */
    public static final String SITES_STATISTICS = SITES + "/{id}/statistics";

    /**
     * List sites by account endpoint.
     * <p>
     * Method: GET<br>
     * Path Variable: {accountId} - Account ID<br>
     * Authentication: Auth0 OAuth2 (ROLE_ADMIN)<br>
     * Returns: List&lt;SiteResponseDto&gt;
     * </p>
     */
    public static final String SITES_BY_ACCOUNT = ACCOUNTS + "/{accountId}/sites";

    /**
     * Create site endpoint.
     * <p>
     * Method: POST<br>
     * Path Variable: {accountId} - Account ID<br>
     * Authentication: Auth0 OAuth2 (ROLE_ADMIN)<br>
     * Returns: SiteResponseDto
     * </p>
     */
    public static final String SITES_CREATE = ACCOUNTS + "/{accountId}/sites";

    /**
     * Activate site endpoint.
     * <p>
     * Method: POST<br>
     * Path Variables: {accountId} - Account ID, {siteId} - Site ID<br>
     * Authentication: Auth0 OAuth2 (ROLE_ADMIN)<br>
     * Returns: SiteResponseDto
     * </p>
     */
    public static final String SITES_ACTIVATE = ACCOUNTS + "/{accountId}/sites/{siteId}/activate";

    /**
     * Deactivate site endpoint.
     * <p>
     * Method: POST<br>
     * Path Variables: {accountId} - Account ID, {siteId} - Site ID<br>
     * Authentication: Auth0 OAuth2 (ROLE_ADMIN)<br>
     * Returns: SiteResponseDto
     * </p>
     */
    public static final String SITES_DEACTIVATE = ACCOUNTS + "/{accountId}/sites/{siteId}/deactivate";

    /**
     * Delete site (alternative path) endpoint.
     * <p>
     * Method: DELETE<br>
     * Path Variables: {accountId} - Account ID, {siteId} - Site ID<br>
     * Authentication: Auth0 OAuth2 (ROLE_ADMIN)
     * </p>
     */
    public static final String SITES_DELETE_BY_ACCOUNT = ACCOUNTS + "/{accountId}/sites/{siteId}";

    // Batches (Admin)
    /**
     * Base path for batch administration endpoints.
     * <p>
     * Requires: ROLE_ADMIN (Auth0)
     * </p>
     */
    public static final String BATCHES_ADMIN = ADMIN_API_BASE + "/batches";

    /**
     * Batch by ID (admin) endpoint.
     * <p>
     * Methods: GET, DELETE<br>
     * Path Variable: {id} - Batch ID<br>
     * Authentication: Auth0 OAuth2 (ROLE_ADMIN)
     * </p>
     */
    public static final String BATCHES_ADMIN_ID = BATCHES_ADMIN + "/{id}";

    // History (User-facing)
    /**
     * Base path for upload history endpoints.
     * <p>
     * Requires: Authenticated user (Auth0 OAuth2)
     * </p>
     */
    public static final String HISTORY = ADMIN_API_BASE + "/history";

    /**
     * Base path for batch history endpoints.
     */
    public static final String HISTORY_BATCHES = HISTORY + "/batches";

    /**
     * Batch history details endpoint.
     * <p>
     * Method: GET<br>
     * Path Variable: {batchId} - Batch ID<br>
     * Authentication: Auth0 OAuth2<br>
     * Returns: BatchDetailDto
     * </p>
     */
    public static final String HISTORY_BATCH_ID = HISTORY_BATCHES + "/{batchId}";

    /**
     * Download single file endpoint.
     * <p>
     * Method: GET<br>
     * Path Variables: {batchId} - Batch ID, {fileId} - File ID<br>
     * Authentication: Auth0 OAuth2<br>
     * Returns: Presigned S3 URL
     * </p>
     */
    public static final String HISTORY_FILE_DOWNLOAD = HISTORY_BATCHES + "/{batchId}/files/{fileId}/download";

    /**
     * Download multiple files as ZIP endpoint.
     * <p>
     * Method: POST<br>
     * Path Variable: {batchId} - Batch ID<br>
     * Authentication: Auth0 OAuth2<br>
     * Returns: ZIP archive (streamed)
     * </p>
     */
    public static final String HISTORY_ZIP_DOWNLOAD = HISTORY_BATCHES + "/{batchId}/download-zip";

    /**
     * Export batch files to Excel endpoint.
     * <p>
     * Method: POST<br>
     * Path Variable: {batchId} - Batch ID<br>
     * Authentication: Auth0 OAuth2<br>
     * Returns: Excel file (.xlsx)
     * </p>
     */
    public static final String HISTORY_EXCEL_EXPORT = HISTORY_BATCHES + "/{batchId}/export-excel";

    /**
     * Get batch errors endpoint.
     * <p>
     * Method: GET<br>
     * Path Variable: {batchId} - Batch ID<br>
     * Authentication: Auth0 OAuth2<br>
     * Returns: PageResponseDto&lt;ErrorSummaryDto&gt;
     * </p>
     */
    public static final String HISTORY_ERRORS = HISTORY_BATCHES + "/{batchId}/errors";

    // Errors (Admin)
    /**
     * Base path for error administration endpoints.
     * <p>
     * Requires: ROLE_ADMIN (Auth0)
     * </p>
     */
    public static final String ERRORS_ADMIN = ADMIN_API_BASE + "/errors";

    /**
     * Export errors endpoint.
     * <p>
     * Method: GET<br>
     * Authentication: Auth0 OAuth2 (ROLE_ADMIN)<br>
     * Returns: CSV file
     * </p>
     */
    public static final String ERRORS_EXPORT = ERRORS_ADMIN + "/export";

    // Comparisons
    /**
     * Base path for file comparison endpoints.
     * <p>
     * Requires: Authenticated user (Auth0 OAuth2)
     * </p>
     */
    public static final String COMPARISONS = ADMIN_API_BASE + "/comparisons";

    /**
     * Comparison by ID endpoint.
     * <p>
     * Methods: GET, DELETE<br>
     * Path Variable: {id} - Comparison ID<br>
     * Authentication: Auth0 OAuth2
     * </p>
     */
    public static final String COMPARISONS_ID = COMPARISONS + "/{id}";

    /**
     * Get comparison results endpoint.
     * <p>
     * Method: GET<br>
     * Path Variable: {id} - Comparison ID<br>
     * Authentication: Auth0 OAuth2<br>
     * Query Params: changeType (optional)<br>
     * Returns: PageResponseDto&lt;ComparisonResultDto&gt;
     * </p>
     */
    public static final String COMPARISONS_RESULTS = COMPARISONS + "/{id}/results";

    /**
     * Get comparison summary endpoint.
     * <p>
     * Method: GET<br>
     * Path Variable: {id} - Comparison ID<br>
     * Authentication: Auth0 OAuth2<br>
     * Returns: ComparisonSummaryDto
     * </p>
     */
    public static final String COMPARISONS_SUMMARY = COMPARISONS + "/{id}/summary";

    /**
     * Get comparisons by batch endpoint.
     * <p>
     * Method: GET<br>
     * Path Variable: {batchId} - Batch ID<br>
     * Authentication: Auth0 OAuth2<br>
     * Returns: List&lt;ComparisonResponseDto&gt;
     * </p>
     */
    public static final String COMPARISONS_BY_BATCH = COMPARISONS + "/by-batch/{batchId}";

    /**
     * Download comparison as ZIP endpoint.
     * <p>
     * Method: GET<br>
     * Path Variable: {id} - Comparison ID<br>
     * Authentication: Auth0 OAuth2<br>
     * Returns: ZIP archive with diffs
     * </p>
     */
    public static final String COMPARISONS_DOWNLOAD = COMPARISONS + "/{id}/download";

    /**
     * Download comparison summary endpoint.
     * <p>
     * Method: GET<br>
     * Path Variable: {id} - Comparison ID<br>
     * Authentication: Auth0 OAuth2<br>
     * Returns: Text file
     * </p>
     */
    public static final String COMPARISONS_SUMMARY_DOWNLOAD = COMPARISONS + "/{id}/summary/download";

    // ==================== Plugins API ====================

    /**
     * Base path for plugin management endpoints.
     * <p>
     * Plugin API uses OAuth2 authentication and requires accountId claim.
     * </p>
     */
    public static final String PLUGINS = ADMIN_API_BASE + "/plugins";

    /**
     * Activate plugin endpoint.
     * <p>
     * Method: POST<br>
     * Path Variable: {pluginId} - Plugin identifier<br>
     * Authentication: OAuth2 (requires accountId claim)<br>
     * Request Body: ActivatePluginRequestDto<br>
     * Returns: PluginActivationResponseDto (201 Created or 200 OK)
     * </p>
     */
    public static final String PLUGINS_ACTIVATE = PLUGINS + "/{pluginId}/activate";

    /**
     * Deactivate plugin endpoint.
     * <p>
     * Method: DELETE<br>
     * Path Variable: {pluginId} - Plugin identifier<br>
     * Authentication: OAuth2 (requires accountId claim)<br>
     * Returns: 204 No Content
     * </p>
     */
    public static final String PLUGINS_DEACTIVATE = PLUGINS + "/{pluginId}/deactivate";

    /**
     * Base path for user's plugin integrations.
     * <p>
     * Requires: Authenticated user (Auth0 OAuth2 with accountId claim)
     * </p>
     */
    public static final String ACCOUNT_PLUGINS = ADMIN_API_BASE + "/account/plugins";

    /**
     * Base path for plugin admin endpoints.
     * <p>
     * Requires: ROLE_ADMIN (Auth0)
     * </p>
     */
    public static final String PLUGINS_ADMIN = ADMIN_API_BASE + "/admin/plugins";

    /**
     * Plugin audit logs endpoint.
     * <p>
     * Method: GET<br>
     * Authentication: OAuth2 (ROLE_ADMIN)<br>
     * Query Params: pluginId, accountId, actionType, success, from, to, page, size<br>
     * Returns: PluginAuditLogPageResponseDto
     * </p>
     */
    public static final String PLUGINS_ADMIN_AUDIT = PLUGINS_ADMIN + "/audit";

    // ==================== Bit BI Plugin API ====================

    /**
     * Base path for Bit BI Plugin API endpoints.
     * <p>
     * This API uses Plugin API Key authentication via X-Plugin-Api-Key header.
     * These endpoints are consumed by Bit BI to retrieve SQL changes.
     * </p>
     */
    public static final String BITBI_PLUGIN_API = ADMIN_API_BASE + "/plugins/bit-bi";

    /**
     * Get SQL changes for a site endpoint.
     * <p>
     * Method: GET<br>
     * Authentication: Plugin API Key (X-Plugin-Api-Key header)<br>
     * Query Params: siteId (UUID, required), since (ISO8601 timestamp, required)<br>
     * Content-Type: text/plain<br>
     * Returns: Concatenated SQL statements or empty body
     * </p>
     */
    public static final String BITBI_SQL_CHANGES = BITBI_PLUGIN_API + "/sql-changes";

    /**
     * List sites for account endpoint.
     * <p>
     * Method: GET<br>
     * Authentication: Plugin API Key (X-Plugin-Api-Key header)<br>
     * Content-Type: application/json<br>
     * Returns: SiteListResponseDto
     * </p>
     */
    public static final String BITBI_SITES = BITBI_PLUGIN_API + "/sites";

    /**
     * List tables for account endpoint.
     * <p>
     * Method: GET<br>
     * Authentication: Plugin API Key (X-Plugin-Api-Key header)<br>
     * Content-Type: application/json<br>
     * Returns: TableListResponseDto with unique table names derived from uploaded files
     * </p>
     */
    public static final String BITBI_TABLES = BITBI_PLUGIN_API + "/tables";

    // Private constructor to prevent instantiation
    private ApiRoutes() {
        throw new UnsupportedOperationException("Utility class - do not instantiate");
    }
}

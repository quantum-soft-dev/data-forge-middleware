/**
 * My Plugins Feature Types
 *
 * Types for displaying and managing user's plugin integrations on the Dashboard.
 * Maps to backend DTOs: AccountPluginSummaryDto, AccountPluginListResponseDto,
 * ActivatePluginRequestDto, PluginActivationResponseDto.
 */

/**
 * Summary of an account's plugin integration.
 * Maps to AccountPluginSummaryDto.java
 *
 * Security: Plugin-specific data (tenantId, etc.) is NOT exposed.
 * Only metadata (name, activation date, last used) is returned.
 */
export interface AccountPluginSummary {
  /** Unique plugin identifier (e.g., "bit-bi") */
  pluginId: string
  /** Human-readable plugin name (e.g., "Bit BI") */
  pluginName: string
  /** Current activation status */
  isActive: boolean
  /** When the plugin was first activated (ISO 8601) */
  activatedAt: string
  /** When the plugin was deactivated, null if active (ISO 8601) */
  deactivatedAt: string | null
  /** When the plugin last received an event, null if never used (ISO 8601) */
  lastUsedAt: string | null
}

/**
 * Paginated response for account plugins list.
 * Maps to AccountPluginListResponseDto.java
 */
export interface AccountPluginListResponse {
  /** List of plugin summaries for the current page */
  content: AccountPluginSummary[]
  /** Current page number (0-indexed) */
  page: number
  /** Page size */
  size: number
  /** Total number of elements across all pages */
  totalElements: number
  /** Total number of pages */
  totalPages: number
}

/**
 * Request DTO for plugin activation.
 * Maps to ActivatePluginRequestDto.java
 */
export interface ActivatePluginRequest {
  /** Plugin-specific data (validated against plugin's JSON Schema) */
  pluginData: Record<string, unknown>
}

/**
 * Response DTO for plugin activation operations.
 * Maps to PluginActivationResponseDto.java
 */
export interface PluginActivationResponse {
  /** Plugin identifier */
  pluginId: string
  /** Human-readable plugin name */
  pluginName: string
  /** Account ID */
  accountId: string
  /** Current activation status */
  isActive: boolean
  /** When the plugin was activated (ISO 8601) */
  activatedAt: string
  /** When the plugin was last used, null if never (ISO 8601) */
  lastUsedAt: string | null
  /**
   * Freshly minted secret, present only for a new activation or a reactivation.
   * bit-bi sends the raw API key, parquet-export sends `login:password`.
   * Shown once and never retrievable again — see {@link parsePluginSecret}.
   */
  apiKey?: string | null
}

/**
 * Query filters for listing account plugins.
 */
export interface AccountPluginsFilters {
  /** Page number (0-indexed) */
  page?: number
  /** Page size (1-100) */
  size?: number
  /** Include deactivated plugins */
  includeInactive?: boolean
}

/**
 * Available plugin for activation.
 * Maps to PluginConfig entity (subset of fields shown to users).
 */
export interface AvailablePlugin {
  /** Unique plugin identifier */
  pluginId: string
  /** Human-readable plugin name */
  displayName: string
  /** Plugin version */
  version: string
  /** Whether the plugin is globally enabled */
  isEnabled: boolean
}

/**
 * Plugin activation form data for Bit BI plugin.
 * Specific to the bit-bi plugin's schema requirements.
 */
export interface BitBiPluginData {
  /** Tenant ID for Bit BI connection */
  tenantId: string
}

// ==================== Plugin Logs Types ====================

/**
 * Plugin action types for user-facing log entries.
 * Maps to PluginActionType.java enum.
 */
export type PluginActionType =
  | 'ACTIVATE'
  | 'DEACTIVATE'
  | 'REACTIVATE'
  | 'EVENT_DISPATCHED'
  | 'EVENT_FAILED'
  | 'EVENT_TIMEOUT'
  | 'SQL_GENERATION_STARTED'
  | 'SQL_GENERATION_COMPLETED'
  | 'SQL_GENERATION_FAILED'
  | 'SQL_GENERATION_DELETED'
  | 'PLUGIN_HISTORY_CLEARED'
  | 'SQL_REGENERATION_STARTED'
  | 'SQL_REGENERATION_COMPLETED'
  | 'SQL_REGENERATION_FAILED'
  | 'REINIT'
  | 'FILES_LISTED'
  | 'LINK_CONSUMED'
  | 'LINK_REJECTED'
  | 'PASSWORD_ROTATED'
  | 'API_KEY_ROTATED'

/**
 * Metadata for SQL generation log entries.
 * Contains statistics and S3 path for generated SQL.
 */
export interface SqlGenerationMetadata {
  /** Unique batch identifier */
  batchId?: string
  /** Site identifier */
  siteId?: string
  /** Number of INSERT statements generated */
  insertCount?: number
  /** Number of UPDATE statements generated */
  updateCount?: number
  /** Number of DELETE statements generated */
  deleteCount?: number
  /** S3 key where SQL file was stored */
  s3Key?: string
  /** Duration in milliseconds */
  durationMs?: number
}

/**
 * User-facing plugin log entry.
 * Maps to UserPluginLogDto.java
 *
 * Security: Excludes sensitive data like client IDs and IP addresses.
 */
export interface PluginLogEntry {
  /** Log entry ID */
  id: number
  /** Type of action */
  actionType: PluginActionType
  /** Whether the operation succeeded */
  success: boolean
  /** Error message if operation failed */
  errorMessage?: string
  /** Structured event metadata */
  metadata?: SqlGenerationMetadata | Record<string, unknown>
  /** When the event occurred (ISO 8601) */
  occurredAt: string
  /** Site identifier (for SQL-related events) */
  siteId?: string
  /** Site domain name (looked up from siteId) */
  siteDomain?: string
}

/**
 * Paginated response for plugin logs.
 * Maps to UserPluginLogPageResponseDto.java
 */
export interface PluginLogPageResponse {
  /** List of log entries for the current page */
  content: PluginLogEntry[]
  /** Current page number (0-indexed) */
  page: number
  /** Page size */
  size: number
  /** Total number of log entries */
  totalElements: number
  /** Total number of pages */
  totalPages: number
}

/**
 * Query filters for plugin logs.
 */
export interface PluginLogFilters {
  /** Plugin ID (e.g., "bit-bi") */
  pluginId: string
  /** Page number (0-indexed) */
  page?: number
  /** Page size (1-100) */
  size?: number
  /** Filter by site ID */
  siteId?: string
  /** Filter by start date (inclusive, ISO 8601) */
  from?: string
  /** Filter by end date (exclusive, ISO 8601) */
  to?: string
}

// ==================== Batch SQL Status Types ====================

/**
 * Batch with SQL generation status.
 * Maps to BatchWithSqlStatusDto.java
 */
export interface BatchSqlStatus {
  /** Unique batch identifier */
  batchId: string
  /** Site identifier */
  siteId: string
  /** Site domain name */
  siteDomain: string
  /** Batch status (e.g., "COMPLETED") */
  status: string
  /** When the batch was completed (ISO 8601) */
  completedAt: string | null
  /** Number of files in the batch */
  fileCount: number
  /** Total size of files in bytes */
  totalSizeBytes: number
  /** Whether this is the baseline batch (SQL shouldn't be generated) */
  isBaseline: boolean
  /** Whether SQL has been generated for this batch */
  hasSql: boolean
  /** SQL generation ID if SQL exists */
  generationId: string | null
  /** Number of INSERT statements (null if no SQL) */
  insertCount: number | null
  /** Number of UPDATE statements (null if no SQL) */
  updateCount: number | null
  /** Number of DELETE statements (null if no SQL) */
  deleteCount: number | null
  /** When SQL was generated (ISO 8601, null if no SQL) */
  generatedAt: string | null
  /** Whether no changes were detected when generation was attempted */
  noChangesDetected: boolean
}

/**
 * Paginated response for batch SQL status list.
 */
export interface BatchSqlStatusPageResponse {
  /** List of batches with SQL status */
  content: BatchSqlStatus[]
  /** Current page number (0-indexed) */
  page: number
  /** Page size */
  size: number
  /** Total number of batches */
  totalElements: number
  /** Total number of pages */
  totalPages: number
}

/**
 * SQL generation summary for list display.
 * Shared with plugin-history feature.
 */
export interface SqlGenerationSummary {
  id: string
  sourceBatchId: string
  comparisonBatchId: string | null
  siteId: string
  siteDomain: string
  createdAt: string
  statementCount: number
  insertCount: number
  updateCount: number
  deleteCount: number
  fileSizeBytes: number
  generationDurationMs: number
  isInitialLoad: boolean
  superseded: boolean
  supersededBy: string | null
}

/**
 * Paginated list response for SQL generations.
 */
export interface SqlGenerationListResponse {
  content: SqlGenerationSummary[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

/**
 * Paginated SQL content for preview.
 */
export interface SqlContentPage {
  generationId: string
  page: number
  pageSize: number
  totalPages: number
  totalStatements: number
  statements: string[]
  hasNext: boolean
  hasPrevious: boolean
}

/**
 * Request for generating SQL for a batch.
 */
export interface GenerateSqlRequest {
  /** Batch ID to generate SQL for */
  batchId: string
}

/**
 * Result of SQL generation.
 */
export interface GenerateSqlResult {
  /** Whether SQL was actually generated (false if skipped) */
  generated: boolean
  /** Generation ID (null if skipped) */
  generationId: string | null
  /** Batch ID that was processed */
  batchId: string
  /** Site ID */
  siteId: string | null
  /** Number of INSERT statements */
  insertCount: number
  /** Number of UPDATE statements */
  updateCount: number
  /** Number of DELETE statements */
  deleteCount: number
  /** Total SQL statements */
  statementCount: number
  /** Message describing the result */
  message: string
  /** Generation timestamp */
  createdAt: string
}

/**
 * Result of regenerating SQL.
 */
export interface RegenerateSqlResult {
  originalGenerationId: string
  newGenerationId: string
  statementCount: number
  insertCount: number
  updateCount: number
  deleteCount: number
  generationDurationMs: number
  regeneratedAt: string
}

/**
 * Result of deleting SQL generation.
 */
export interface DeleteGenerationResult {
  generationId: string
  deletedFromS3: boolean
  deletedAt: string
}

/**
 * Query filters for batch SQL status.
 */
export interface BatchSqlFilters {
  /** Plugin ID */
  pluginId: string
  /** Page number (0-indexed) */
  page?: number
  /** Page size */
  size?: number
  /** Filter by site ID */
  siteId?: string
}

/**
 * Parameters for SQL content query.
 */
export interface SqlContentParams {
  /** Plugin ID */
  pluginId: string
  /** Generation ID */
  generationId: string
  /** Page number (0-indexed) */
  page?: number
  /** Page size */
  size?: number
}

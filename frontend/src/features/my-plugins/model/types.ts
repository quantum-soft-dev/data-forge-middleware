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
}

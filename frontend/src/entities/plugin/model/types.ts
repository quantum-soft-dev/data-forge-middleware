/**
 * Plugin Entity Types
 *
 * Domain model for plugin administration with audit logging.
 * Maps to backend DTOs: PluginConfigResponseDto, PluginAuditLogEntryDto
 */

/**
 * Plugin action types as defined in PluginActionType.java
 */
export type PluginActionType =
  | 'ACTIVATE'
  | 'DEACTIVATE'
  | 'REACTIVATE'
  | 'EVENT_DISPATCHED'
  | 'EVENT_FAILED'
  | 'EVENT_TIMEOUT'

/**
 * Registered plugin configuration
 * Maps to PluginConfigResponseDto.java
 */
export interface PluginConfig {
  pluginId: string
  displayName: string
  version: string
  isEnabled: boolean
  supportedEvents: string[]
  createdAt: string // ISO 8601
  updatedAt: string // ISO 8601
}

/**
 * Plugin audit log entry
 * Maps to PluginAuditLogEntryDto.java
 */
export interface PluginAuditLogEntry {
  id: number
  pluginId: string
  accountId: string | null
  clientId: string | null
  actionType: PluginActionType
  requestBodyHash: string | null
  requestBodySize: number | null
  responseStatus: number | null
  success: boolean
  errorMessage: string | null
  ipAddress: string | null
  durationMs: number | null
  occurredAt: string // ISO 8601
}

/**
 * Paginated audit log response
 * Maps to PluginAuditLogPageResponseDto.java
 */
export interface PluginAuditLogPageResponse {
  content: PluginAuditLogEntry[]
  page: number // 0-indexed from API
  size: number
  totalElements: number
  totalPages: number
}

/**
 * Audit log query filters
 */
export interface AuditLogFilters {
  pluginId?: string
  accountId?: string
  actionType?: PluginActionType
  success?: boolean
  from?: string // ISO 8601
  to?: string // ISO 8601
  page?: number
  size?: number
}

// ==================== Admin Account-Plugin Types (Feature 014) ====================

/**
 * Admin view of account-plugin activation
 * Maps to AdminAccountPluginDto.java
 */
export interface AdminAccountPlugin {
  accountId: string
  pluginId: string
  pluginName: string
  isActive: boolean
  activatedAt: string | null // ISO 8601
  deactivatedAt: string | null // ISO 8601
  lastUsedAt: string | null // ISO 8601
  generationCount: number
}

/**
 * Paginated admin account-plugin response
 * Maps to AdminAccountPluginPageDto.java
 */
export interface AdminAccountPluginPageResponse {
  content: AdminAccountPlugin[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
}

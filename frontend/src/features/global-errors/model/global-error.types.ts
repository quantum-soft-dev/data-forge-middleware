/**
 * Global Error Types
 *
 * TypeScript types for global error handling feature.
 */

/**
 * Error severity levels.
 */
export type ErrorSeverity = 'CRITICAL' | 'ERROR' | 'WARNING' | 'INFO'

/**
 * Summary of a global error for list display.
 */
export interface GlobalErrorSummary {
  id: string
  siteId: string
  siteName: string
  type: string
  title: string
  severity: ErrorSeverity
  isRead: boolean
  occurredAt: string
}

/**
 * Full details of a global error.
 */
export interface GlobalErrorResponse {
  id: string
  siteId: string
  siteName: string
  type: string
  title: string
  message: string
  stackTrace: string | null
  clientVersion: string | null
  metadata: Record<string, unknown> | null
  severity: ErrorSeverity
  isRead: boolean
  occurredAt: string
}

/**
 * Paginated response for global errors.
 */
export interface GlobalErrorPageResponse {
  content: GlobalErrorSummary[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

/**
 * Unread count response.
 */
export interface UnreadCountResponse {
  count: number
}

/**
 * Request to mark multiple errors as read.
 */
export interface MarkAsReadRequest {
  errorIds: string[]
}

/**
 * Response for mark-as-read operations.
 */
export interface MarkAsReadResponse {
  markedCount: number
}

/**
 * Query parameters for listing global errors.
 */
export interface GlobalErrorsQueryParams {
  page?: number
  size?: number
  unreadOnly?: boolean
}

/**
 * Severity color mapping for UI.
 */
export const SEVERITY_COLORS: Record<ErrorSeverity, string> = {
  CRITICAL: 'text-red-600 bg-red-100',
  ERROR: 'text-orange-600 bg-orange-100',
  WARNING: 'text-yellow-600 bg-yellow-100',
  INFO: 'text-blue-600 bg-blue-100',
}

/**
 * Severity badge colors for UI.
 */
export const SEVERITY_BADGE_VARIANTS: Record<ErrorSeverity, 'destructive' | 'default' | 'secondary' | 'outline'> = {
  CRITICAL: 'destructive',
  ERROR: 'destructive',
  WARNING: 'secondary',
  INFO: 'outline',
}

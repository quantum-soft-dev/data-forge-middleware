/**
 * Type definitions for Plugin History feature.
 *
 * @module features/plugin-history/model/types
 */

/**
 * SQL generation summary for list display.
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
 * Summary shown before clearing history.
 */
export interface HistoryClearSummary {
  accountId: string
  pluginId: string
  generationCount: number
  totalFileSizeBytes: number
  pluginWillBeDeactivated: boolean
  hasActiveBatches: boolean
}

/**
 * Result after clearing history.
 */
export interface HistoryClearResult {
  deletedGenerations: number
  deletedFilesCount: number
  deletedTotalBytes: number
  failedS3Keys: string[]
  pluginDeactivated: boolean
  clearedAt: string
}

/**
 * Filter parameters for listing generations.
 */
export interface GenerationListFilters {
  pluginId: string
  accountId: string
  page?: number
  size?: number
  includeSuperseded?: boolean
}

/**
 * Parameters for getting SQL content.
 */
export interface SqlContentParams {
  pluginId: string
  accountId: string
  generationId: string
  page?: number
  size?: number
}

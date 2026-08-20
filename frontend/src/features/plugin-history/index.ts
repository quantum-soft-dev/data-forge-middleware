/**
 * Plugin History Feature
 *
 * Provides components and hooks for admin plugin history management.
 *
 * @module features/plugin-history
 */

// API exports
export {
  pluginHistoryApi,
  fetchGenerations,
  fetchGeneration,
  fetchSqlContent,
  downloadSqlFile,
  fetchHistorySummary,
  clearHistory,
} from './api/plugin-history.api'

// Query exports
export {
  pluginHistoryKeys,
  useGenerationsQuery,
  useGenerationQuery,
  useSqlContentQuery,
  useHistorySummaryQuery,
  useDownloadSqlFile,
  useClearHistoryMutation,
} from './api/plugin-history.queries'

// UI component exports
export { GenerationListTable } from './ui/GenerationListTable'
export { SqlContentViewer } from './ui/SqlContentViewer'
export { ClearHistoryDialog } from './ui/ClearHistoryDialog'

// Type exports
export type {
  SqlGenerationSummary,
  SqlGenerationListResponse,
  SqlContentPage,
  HistoryClearSummary,
  HistoryClearResult,
  GenerationListFilters,
  SqlContentParams,
} from './model/types'

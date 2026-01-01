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
  regenerateSql,
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
  useRegenerateSqlMutation,
} from './api/plugin-history.queries'

// UI component exports
export { GenerationListTable } from './ui/GenerationListTable'
export { SqlContentViewer } from './ui/SqlContentViewer'
export { ClearHistoryDialog } from './ui/ClearHistoryDialog'
export { RegenerateDialog } from './ui/RegenerateDialog'

// Type exports
export type {
  SqlGenerationSummary,
  SqlGenerationListResponse,
  SqlContentPage,
  HistoryClearSummary,
  HistoryClearResult,
  RegenerateResult,
  GenerationListFilters,
  SqlContentParams,
} from './model/types'

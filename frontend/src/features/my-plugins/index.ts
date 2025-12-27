/**
 * My Plugins Feature - Barrel Export
 *
 * Exports types, API functions, and hooks for user-facing plugin management.
 */

// Types
export type {
  AccountPluginSummary,
  AccountPluginListResponse,
  ActivatePluginRequest,
  PluginActivationResponse,
  AccountPluginsFilters,
  AvailablePlugin,
  BitBiPluginData,
  // Plugin Logs Types
  PluginActionType,
  PluginLogEntry,
  PluginLogPageResponse,
  PluginLogFilters,
  SqlGenerationMetadata,
} from './model/types'

// API Functions
export {
  myPluginsApi,
  fetchAccountPlugins,
  fetchAvailablePlugins,
  activatePlugin,
  deactivatePlugin,
} from './api/myPluginsApi'

export {
  pluginLogsApi,
  fetchPluginLogs,
} from './api/pluginLogsApi'

// Query Hooks
export {
  useAccountPluginsQuery,
  useAvailablePluginsQuery,
} from './api/myPluginsQueries'

export { usePluginLogsQuery } from './api/pluginLogsQueries'

// Mutation Hooks
export {
  useActivatePluginMutation,
  useDeactivatePluginMutation,
} from './api/myPluginsMutations'

// UI Components
export { PluginCard } from './ui/PluginCard'
export { PluginList } from './ui/PluginList'
export { PluginActivationDialog } from './ui/PluginActivationDialog'
export { PluginLogsTab } from './ui/PluginLogsTab'

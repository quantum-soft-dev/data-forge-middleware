/**
 * TanStack Query hooks for user-facing plugin logs.
 *
 * @module features/my-plugins/api/pluginLogsQueries
 */

import { useQuery } from '@tanstack/react-query'
import { pluginKeys } from '@/entities/plugin/api/keys'
import { fetchPluginLogs } from './pluginLogsApi'
import type { PluginLogFilters } from '../model/types'

/**
 * Hook to fetch plugin logs for a specific plugin with filters.
 *
 * Returns paginated list of log entries including:
 * - Plugin activation/deactivation events
 * - SQL generation started/completed/failed events
 * - Event dispatch status
 *
 * @param filters - Query filters (pluginId, page, size, siteId, from, to)
 * @returns Query result with paginated list of plugin logs
 */
export function usePluginLogsQuery(filters: PluginLogFilters) {
  const { pluginId, page = 0, size = 20, siteId, from, to } = filters

  return useQuery({
    queryKey: pluginKeys.pluginLogsList(pluginId, { page, size, siteId, from, to }),
    queryFn: () => fetchPluginLogs(filters),
    enabled: !!pluginId, // Don't fetch if pluginId is empty
    staleTime: 30000, // 30 seconds - logs can change frequently
  })
}

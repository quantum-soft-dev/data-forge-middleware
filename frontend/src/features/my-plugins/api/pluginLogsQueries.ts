/**
 * TanStack Query hooks for user-facing plugin logs.
 *
 * @module features/my-plugins/api/pluginLogsQueries
 */

import { useQuery } from '@tanstack/react-query'
import { pluginKeys } from '@/entities/plugin/api/keys'
import { fetchPluginLogs } from './pluginLogsApi'

/**
 * Hook to fetch plugin logs for a specific plugin.
 *
 * Returns paginated list of log entries including:
 * - Plugin activation/deactivation events
 * - SQL generation started/completed/failed events
 * - Event dispatch status
 *
 * @param pluginId - Plugin identifier (e.g., "bit-bi")
 * @param page - Page number (0-indexed)
 * @returns Query result with paginated list of plugin logs
 */
export function usePluginLogsQuery(pluginId: string, page: number = 0) {
  return useQuery({
    queryKey: pluginKeys.pluginLogsList(pluginId, page),
    queryFn: () => fetchPluginLogs({ pluginId, page }),
    enabled: !!pluginId, // Don't fetch if pluginId is empty
    staleTime: 30000, // 30 seconds - logs can change frequently
  })
}

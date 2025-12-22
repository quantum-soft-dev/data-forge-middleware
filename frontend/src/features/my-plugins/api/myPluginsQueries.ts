/**
 * TanStack Query hooks for user-facing plugin management.
 *
 * @module features/my-plugins/api/myPluginsQueries
 */

import { useQuery } from '@tanstack/react-query'
import { pluginKeys } from '@/entities/plugin/api/keys'
import { fetchAccountPlugins, fetchAvailablePlugins } from './myPluginsApi'

/**
 * Hook to fetch account's plugin integrations.
 *
 * @param includeInactive - Whether to include deactivated plugins
 * @returns Query result with paginated list of account plugins
 */
export function useAccountPluginsQuery(includeInactive: boolean = false) {
  return useQuery({
    queryKey: pluginKeys.accountPluginList(includeInactive),
    queryFn: () => fetchAccountPlugins({ includeInactive }),
    staleTime: 30000, // 30 seconds - balance between freshness and performance
  })
}

/**
 * Hook to fetch all available plugins for activation.
 * Returns list of plugins registered in the system that user can activate.
 *
 * @returns Query result with list of available plugins
 */
export function useAvailablePluginsQuery() {
  return useQuery({
    queryKey: pluginKeys.list(),
    queryFn: () => fetchAvailablePlugins(),
    staleTime: 60000, // 1 minute - available plugins rarely change
  })
}

/**
 * TanStack Query hooks for plugin administration.
 *
 * @module features/plugin-admin/api/pluginQueries
 */

import { useQuery } from '@tanstack/react-query'
import { pluginKeys } from '@/entities/plugin/api/keys'
import { fetchPlugins, fetchAuditLogs, fetchPluginAuditLogs } from './pluginAdminApi'
import type { AuditLogFilters } from '@/entities/plugin/model/types'

/**
 * Hook to fetch all registered plugins.
 *
 * @returns Query result with list of plugins
 */
export function usePluginsQuery() {
  return useQuery({
    queryKey: pluginKeys.list(),
    queryFn: () => fetchPlugins(),
    staleTime: 60000, // 1 minute - plugins rarely change
  })
}

/**
 * Hook to fetch paginated audit logs with filters.
 *
 * @param filters - Optional filters for audit logs
 * @returns Query result with paginated audit logs
 */
export function useAuditLogsQuery(filters: AuditLogFilters = {}) {
  // Apply defaults
  const effectiveFilters: AuditLogFilters = {
    page: 0,
    size: 50,
    ...filters,
  }

  return useQuery({
    queryKey: pluginKeys.auditLogList(effectiveFilters),
    queryFn: () => fetchAuditLogs(effectiveFilters),
    staleTime: 10000, // 10 seconds - audit logs should be relatively fresh
  })
}

/**
 * Hook to fetch audit logs for a specific plugin.
 *
 * @param pluginId - Plugin identifier
 * @param page - Page number (0-indexed)
 * @param size - Page size
 * @returns Query result with paginated audit logs
 */
export function usePluginAuditLogsQuery(
  pluginId: string,
  page: number = 0,
  size: number = 50
) {
  return useQuery({
    queryKey: pluginKeys.auditLogByPlugin(pluginId, page, size),
    queryFn: () => fetchPluginAuditLogs(pluginId, page, size),
    enabled: !!pluginId,
    staleTime: 10000,
  })
}

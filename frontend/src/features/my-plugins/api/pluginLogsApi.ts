/**
 * Plugin Logs API Client
 *
 * API client functions for user-facing plugin log viewing.
 * Uses Axios instance with Auth0 authentication.
 */

import { apiClient } from '@/shared/api/client'
import { ACCOUNT_PLUGIN_LOGS } from '@/shared/api/apiRoutes'
import type { PluginLogPageResponse, PluginLogFilters } from '../model/types'

/**
 * Fetch plugin logs for the authenticated account.
 *
 * @param filters - Query filters (pluginId, page, size, siteId, from, to)
 * @returns Paginated list of plugin log entries
 */
export async function fetchPluginLogs(
  filters: PluginLogFilters
): Promise<PluginLogPageResponse> {
  const { pluginId, page = 0, size = 20, siteId, from, to } = filters

  const params = new URLSearchParams()
  params.append('page', String(page))
  params.append('size', String(size))

  if (siteId) {
    params.append('siteId', siteId)
  }
  if (from) {
    params.append('from', from)
  }
  if (to) {
    params.append('to', to)
  }

  const url = `${ACCOUNT_PLUGIN_LOGS(pluginId)}?${params.toString()}`

  const response = await apiClient.get<PluginLogPageResponse>(url)
  return response.data
}

export const pluginLogsApi = {
  fetchPluginLogs,
}

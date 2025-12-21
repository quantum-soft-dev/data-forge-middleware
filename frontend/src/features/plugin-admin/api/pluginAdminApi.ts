/**
 * Plugin Admin API Client
 *
 * API client functions for plugin administration endpoints.
 * Uses Axios instance with JWT authentication.
 */

import { apiClient } from '@/shared/api/client'
import {
  ADMIN_PLUGINS,
  ADMIN_PLUGINS_AUDIT,
  ADMIN_PLUGINS_AUDIT_BY_PLUGIN,
} from '@/shared/api/apiRoutes'
import type {
  PluginConfig,
  PluginAuditLogPageResponse,
  AuditLogFilters,
} from '@/entities/plugin/model/types'

/**
 * Fetch all registered plugins
 */
export async function fetchPlugins(): Promise<PluginConfig[]> {
  const response = await apiClient.get<PluginConfig[]>(ADMIN_PLUGINS)
  return response.data
}

/**
 * Fetch audit logs with optional filters
 */
export async function fetchAuditLogs(
  filters: AuditLogFilters = {}
): Promise<PluginAuditLogPageResponse> {
  const params = new URLSearchParams()

  if (filters.page !== undefined) {
    params.append('page', String(filters.page))
  }
  if (filters.size !== undefined) {
    params.append('size', String(filters.size))
  }
  if (filters.pluginId) {
    params.append('pluginId', filters.pluginId)
  }
  if (filters.accountId) {
    params.append('accountId', filters.accountId)
  }
  if (filters.actionType) {
    params.append('actionType', filters.actionType)
  }
  if (filters.success !== undefined) {
    params.append('success', String(filters.success))
  }
  if (filters.from) {
    params.append('from', filters.from)
  }
  if (filters.to) {
    params.append('to', filters.to)
  }

  const queryString = params.toString()
  const url = queryString ? `${ADMIN_PLUGINS_AUDIT}?${queryString}` : ADMIN_PLUGINS_AUDIT

  const response = await apiClient.get<PluginAuditLogPageResponse>(url)
  return response.data
}

/**
 * Fetch audit logs for a specific plugin
 */
export async function fetchPluginAuditLogs(
  pluginId: string,
  page: number = 0,
  size: number = 50
): Promise<PluginAuditLogPageResponse> {
  const params = new URLSearchParams()
  params.append('page', String(page))
  params.append('size', String(size))

  const response = await apiClient.get<PluginAuditLogPageResponse>(
    `${ADMIN_PLUGINS_AUDIT_BY_PLUGIN(pluginId)}?${params.toString()}`
  )
  return response.data
}

export const pluginAdminApi = {
  fetchPlugins,
  fetchAuditLogs,
  fetchPluginAuditLogs,
}

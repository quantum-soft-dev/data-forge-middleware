/**
 * My Plugins API Client
 *
 * API client functions for user-facing plugin management.
 * Uses Axios instance with Auth0 authentication.
 */

import { apiClient } from '@/shared/api/client'
import {
  ACCOUNT_PLUGINS,
  ADMIN_PLUGINS,
  PLUGINS_ACTIVATE,
  PLUGINS_DEACTIVATE,
} from '@/shared/api/apiRoutes'
import type {
  AccountPluginListResponse,
  AccountPluginsFilters,
  ActivatePluginRequest,
  PluginActivationResponse,
  AvailablePlugin,
} from '../model/types'
import type { PluginConfig } from '@/entities/plugin/model/types'

/**
 * Fetch account's plugin integrations
 */
export async function fetchAccountPlugins(
  filters: AccountPluginsFilters = {}
): Promise<AccountPluginListResponse> {
  const params = new URLSearchParams()

  if (filters.page !== undefined) {
    params.append('page', String(filters.page))
  }
  if (filters.size !== undefined) {
    params.append('size', String(filters.size))
  }
  if (filters.includeInactive !== undefined) {
    params.append('includeInactive', String(filters.includeInactive))
  }

  const queryString = params.toString()
  const url = queryString ? `${ACCOUNT_PLUGINS}?${queryString}` : ACCOUNT_PLUGINS

  const response = await apiClient.get<AccountPluginListResponse>(url)
  return response.data
}

/**
 * Fetch all available plugins (admin endpoint, returns all registered plugins)
 * Used to show available plugins that user can activate.
 */
export async function fetchAvailablePlugins(): Promise<AvailablePlugin[]> {
  const response = await apiClient.get<PluginConfig[]>(ADMIN_PLUGINS)
  // Map PluginConfig to AvailablePlugin (subset of fields for user view)
  return response.data.map((config) => ({
    pluginId: config.pluginId,
    displayName: config.displayName,
    version: config.version,
    isEnabled: config.isEnabled,
  }))
}

/**
 * Activate a plugin for the current account
 *
 * @param pluginId - Plugin identifier (e.g., "bit-bi")
 * @param request - Activation request with plugin-specific data
 * @returns PluginActivationResponseDto
 */
export async function activatePlugin(
  pluginId: string,
  request: ActivatePluginRequest
): Promise<PluginActivationResponse> {
  const response = await apiClient.post<PluginActivationResponse>(
    PLUGINS_ACTIVATE(pluginId),
    request
  )
  return response.data
}

/**
 * Deactivate a plugin for the current account
 *
 * @param pluginId - Plugin identifier (e.g., "bit-bi")
 */
export async function deactivatePlugin(pluginId: string): Promise<void> {
  await apiClient.delete(PLUGINS_DEACTIVATE(pluginId))
}

export const myPluginsApi = {
  fetchAccountPlugins,
  fetchAvailablePlugins,
  activatePlugin,
  deactivatePlugin,
}

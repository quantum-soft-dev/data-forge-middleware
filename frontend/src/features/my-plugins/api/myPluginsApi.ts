/**
 * My Plugins API Client
 *
 * API client functions for user-facing plugin management.
 * Uses Axios instance with Auth0 authentication.
 */

import { apiClient } from '@/shared/api/client'
import {
  ACCOUNT_PLUGINS,
  ACCOUNT_PLUGIN_ROTATE_API_KEY,
  ACCOUNT_PLUGIN_ROTATE_PASSWORD,
  PLUGINS,
  PLUGINS_ACTIVATE,
  PLUGINS_DEACTIVATE,
} from '@/shared/api/apiRoutes'
import { PARQUET_EXPORT_PLUGIN_ID } from '../model/pluginSecret'
import type { PluginSecret } from '../model/pluginSecret'
import type {
  AccountPluginListResponse,
  AccountPluginsFilters,
  ActivatePluginRequest,
  PluginActivationResponse,
  AvailablePlugin,
} from '../model/types'

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
 * Fetch all available (enabled) plugins for activation.
 * Uses the user-facing endpoint that doesn't require admin role.
 */
export async function fetchAvailablePlugins(): Promise<AvailablePlugin[]> {
  const response = await apiClient.get<AvailablePlugin[]>(PLUGINS)
  return response.data
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

/** Plugin id of the Bit BI integration, whose secret is a single API key. */
const BIT_BI_PLUGIN_ID = 'bit-bi'

/**
 * Whether a plugin can reissue its secret.
 *
 * Rotation is the only way back to a working integration once the one-shot
 * activation secret is lost, and only these two plugins expose an endpoint.
 */
export function supportsSecretRotation(pluginId: string): boolean {
  return pluginId === BIT_BI_PLUGIN_ID || pluginId === PARQUET_EXPORT_PLUGIN_ID
}

/**
 * Reissue a plugin's secret. The previous value stops authenticating immediately
 * and the new one is returned exactly once.
 *
 * @param pluginId - Plugin identifier, must satisfy {@link supportsSecretRotation}
 * @returns the freshly issued secret
 */
export async function rotatePluginSecret(pluginId: string): Promise<PluginSecret> {
  if (pluginId === BIT_BI_PLUGIN_ID) {
    const response = await apiClient.post<{ apiKey: string }>(ACCOUNT_PLUGIN_ROTATE_API_KEY)
    return { kind: 'api-key', pluginId, value: response.data.apiKey }
  }

  if (pluginId === PARQUET_EXPORT_PLUGIN_ID) {
    const response = await apiClient.post<{ login: string; password: string }>(
      ACCOUNT_PLUGIN_ROTATE_PASSWORD
    )
    return {
      kind: 'basic-auth',
      pluginId,
      login: response.data.login,
      password: response.data.password,
    }
  }

  throw new Error(`Plugin ${pluginId} has no rotatable secret`)
}

export const myPluginsApi = {
  fetchAccountPlugins,
  fetchAvailablePlugins,
  activatePlugin,
  deactivatePlugin,
  rotatePluginSecret,
}

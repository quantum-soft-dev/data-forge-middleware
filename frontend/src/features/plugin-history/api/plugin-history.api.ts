/**
 * Plugin History API Client
 *
 * API client functions for admin plugin history management.
 * Uses Axios instance with Auth0 authentication.
 *
 * @module features/plugin-history/api/plugin-history.api
 */

import { apiClient } from '@/shared/api/client'
import type {
  SqlGenerationListResponse,
  SqlGenerationSummary,
  SqlContentPage,
  HistoryClearSummary,
  HistoryClearResult,
  RegenerateResult,
  GenerationListFilters,
  SqlContentParams,
} from '../model/types'

// API Routes for Plugin History (Admin)
const ADMIN_PLUGINS_BASE = '/v1/admin/plugins'

const buildGenerationsUrl = (pluginId: string, accountId: string) =>
  `${ADMIN_PLUGINS_BASE}/${pluginId}/accounts/${accountId}/generations`

const buildGenerationUrl = (pluginId: string, accountId: string, generationId: string) =>
  `${buildGenerationsUrl(pluginId, accountId)}/${generationId}`

const buildHistoryUrl = (pluginId: string, accountId: string) =>
  `${ADMIN_PLUGINS_BASE}/${pluginId}/accounts/${accountId}/history`

/**
 * Fetches paginated list of SQL generations for an account-plugin.
 */
export async function fetchGenerations(
  filters: GenerationListFilters
): Promise<SqlGenerationListResponse> {
  const { pluginId, accountId, page = 0, size = 20, includeSuperseded = false } = filters

  const params = new URLSearchParams()
  params.append('page', String(page))
  params.append('size', String(size))
  params.append('includeSuperseded', String(includeSuperseded))

  const url = `${buildGenerationsUrl(pluginId, accountId)}?${params.toString()}`
  const response = await apiClient.get<SqlGenerationListResponse>(url)
  return response.data
}

/**
 * Fetches a single SQL generation by ID.
 */
export async function fetchGeneration(
  pluginId: string,
  accountId: string,
  generationId: string
): Promise<SqlGenerationSummary> {
  const url = buildGenerationUrl(pluginId, accountId, generationId)
  const response = await apiClient.get<SqlGenerationSummary>(url)
  return response.data
}

/**
 * Fetches paginated SQL content for preview.
 */
export async function fetchSqlContent(
  params: SqlContentParams
): Promise<SqlContentPage> {
  const { pluginId, accountId, generationId, page = 0, size = 100 } = params

  const urlParams = new URLSearchParams()
  urlParams.append('page', String(page))
  urlParams.append('size', String(size))

  const url = `${buildGenerationUrl(pluginId, accountId, generationId)}/content?${urlParams.toString()}`
  const response = await apiClient.get<SqlContentPage>(url)
  return response.data
}

/**
 * Downloads the complete SQL file.
 * Returns the raw SQL content as text.
 */
export async function downloadSqlFile(
  pluginId: string,
  accountId: string,
  generationId: string
): Promise<string> {
  const url = `${buildGenerationUrl(pluginId, accountId, generationId)}/download`
  const response = await apiClient.get<string>(url, {
    headers: { Accept: 'text/plain' },
    responseType: 'text',
  })
  return response.data
}

/**
 * Fetches summary of what will be deleted when clearing history.
 */
export async function fetchHistorySummary(
  pluginId: string,
  accountId: string
): Promise<HistoryClearSummary> {
  const url = `${buildHistoryUrl(pluginId, accountId)}/summary`
  const response = await apiClient.get<HistoryClearSummary>(url)
  return response.data
}

/**
 * Clears all plugin history for an account.
 * Requires confirm=true query parameter.
 */
export async function clearHistory(
  pluginId: string,
  accountId: string
): Promise<HistoryClearResult> {
  const url = `${buildHistoryUrl(pluginId, accountId)}?confirm=true`
  const response = await apiClient.delete<HistoryClearResult>(url)
  return response.data
}

/**
 * Regenerates SQL for a specific generation.
 */
export async function regenerateSql(
  pluginId: string,
  accountId: string,
  generationId: string
): Promise<RegenerateResult> {
  const url = `${buildGenerationUrl(pluginId, accountId, generationId)}/regenerate`
  const response = await apiClient.post<RegenerateResult>(url)
  return response.data
}

export const pluginHistoryApi = {
  fetchGenerations,
  fetchGeneration,
  fetchSqlContent,
  downloadSqlFile,
  fetchHistorySummary,
  clearHistory,
  regenerateSql,
}

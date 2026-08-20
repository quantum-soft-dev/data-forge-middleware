/**
 * Batch SQL API Client
 *
 * API client functions for user-facing batch SQL management.
 * Uses Axios instance with Auth0 authentication.
 */

import { apiClient } from '@/shared/api/client'
import {
  ACCOUNT_PLUGIN_BATCHES,
  ACCOUNT_PLUGIN_GENERATE_SQL,
  ACCOUNT_PLUGIN_GENERATIONS,
  ACCOUNT_PLUGIN_GENERATION,
  ACCOUNT_PLUGIN_GENERATION_CONTENT,
  ACCOUNT_PLUGIN_GENERATION_DOWNLOAD,
} from '@/shared/api/apiRoutes'
import type {
  BatchSqlStatusPageResponse,
  BatchSqlFilters,
  GenerateSqlRequest,
  GenerateSqlResult,
  SqlGenerationListResponse,
  SqlContentPage,
  SqlContentParams,
  DeleteGenerationResult,
} from '../model/types'

/**
 * Fetch batches with SQL generation status.
 */
export async function fetchBatchesWithSqlStatus(
  filters: BatchSqlFilters
): Promise<BatchSqlStatusPageResponse> {
  const { pluginId, page = 0, size = 20, siteId } = filters

  const params = new URLSearchParams()
  params.append('page', String(page))
  params.append('size', String(size))

  if (siteId) {
    params.append('siteId', siteId)
  }

  const url = `${ACCOUNT_PLUGIN_BATCHES(pluginId)}?${params.toString()}`
  const response = await apiClient.get<BatchSqlStatusPageResponse>(url)
  return response.data
}

/**
 * Generate SQL for a specific batch.
 */
export async function generateSql(
  pluginId: string,
  request: GenerateSqlRequest
): Promise<GenerateSqlResult> {
  const url = ACCOUNT_PLUGIN_GENERATE_SQL(pluginId)
  const response = await apiClient.post<GenerateSqlResult>(url, request)
  return response.data
}

/**
 * Fetch SQL generations list.
 */
export async function fetchGenerations(
  pluginId: string,
  page: number = 0,
  size: number = 20,
  includeSuperseded: boolean = false
): Promise<SqlGenerationListResponse> {
  const params = new URLSearchParams()
  params.append('page', String(page))
  params.append('size', String(size))
  params.append('includeSuperseded', String(includeSuperseded))

  const url = `${ACCOUNT_PLUGIN_GENERATIONS(pluginId)}?${params.toString()}`
  const response = await apiClient.get<SqlGenerationListResponse>(url)
  return response.data
}

/**
 * Fetch paginated SQL content for a generation.
 */
export async function fetchSqlContent(
  params: SqlContentParams
): Promise<SqlContentPage> {
  const { pluginId, generationId, page = 0, size = 50 } = params

  const searchParams = new URLSearchParams()
  searchParams.append('page', String(page))
  searchParams.append('size', String(size))

  const url = `${ACCOUNT_PLUGIN_GENERATION_CONTENT(pluginId, generationId)}?${searchParams.toString()}`
  const response = await apiClient.get<SqlContentPage>(url)
  return response.data
}

/**
 * Download SQL file for a generation.
 */
export async function downloadSqlFile(
  pluginId: string,
  generationId: string
): Promise<void> {
  const url = ACCOUNT_PLUGIN_GENERATION_DOWNLOAD(pluginId, generationId)
  const response = await apiClient.get<Blob>(url, {
    responseType: 'blob',
  })

  // Create download link
  const blob = new Blob([response.data], { type: 'application/sql' })
  const downloadUrl = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = downloadUrl
  link.download = `sql-generation-${generationId}.sql`
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(downloadUrl)
}

/**
 * Delete a SQL generation.
 */
export async function deleteGeneration(
  pluginId: string,
  generationId: string
): Promise<DeleteGenerationResult> {
  const url = ACCOUNT_PLUGIN_GENERATION(pluginId, generationId)
  const response = await apiClient.delete<DeleteGenerationResult>(url)
  return response.data
}

export const batchSqlApi = {
  fetchBatchesWithSqlStatus,
  generateSql,
  fetchGenerations,
  fetchSqlContent,
  downloadSqlFile,
  deleteGeneration,
}

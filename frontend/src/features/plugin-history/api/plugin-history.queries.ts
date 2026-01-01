/**
 * TanStack Query hooks for plugin history management.
 *
 * @module features/plugin-history/api/plugin-history.queries
 */

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  fetchGenerations,
  fetchGeneration,
  fetchSqlContent,
  downloadSqlFile,
  fetchHistorySummary,
  clearHistory,
  regenerateSql,
} from './plugin-history.api'
import type {
  GenerationListFilters,
  SqlContentParams,
} from '../model/types'

/**
 * Query keys for plugin history queries.
 */
export const pluginHistoryKeys = {
  all: ['plugin-history'] as const,
  generations: (pluginId: string, accountId: string) =>
    [...pluginHistoryKeys.all, 'generations', pluginId, accountId] as const,
  generationList: (filters: GenerationListFilters) =>
    [...pluginHistoryKeys.generations(filters.pluginId, filters.accountId), filters] as const,
  generation: (pluginId: string, accountId: string, generationId: string) =>
    [...pluginHistoryKeys.generations(pluginId, accountId), generationId] as const,
  sqlContent: (params: SqlContentParams) =>
    [...pluginHistoryKeys.generation(params.pluginId, params.accountId, params.generationId), 'content', params.page, params.size] as const,
  historySummary: (pluginId: string, accountId: string) =>
    [...pluginHistoryKeys.all, 'history-summary', pluginId, accountId] as const,
}

/**
 * Hook to fetch paginated list of SQL generations.
 */
export function useGenerationsQuery(filters: GenerationListFilters) {
  return useQuery({
    queryKey: pluginHistoryKeys.generationList(filters),
    queryFn: () => fetchGenerations(filters),
    staleTime: 30000, // 30 seconds
  })
}

/**
 * Hook to fetch a single SQL generation.
 */
export function useGenerationQuery(
  pluginId: string,
  accountId: string,
  generationId: string,
  enabled = true
) {
  return useQuery({
    queryKey: pluginHistoryKeys.generation(pluginId, accountId, generationId),
    queryFn: () => fetchGeneration(pluginId, accountId, generationId),
    enabled,
    staleTime: 60000, // 1 minute
  })
}

/**
 * Hook to fetch paginated SQL content for preview.
 */
export function useSqlContentQuery(params: SqlContentParams, enabled = true) {
  return useQuery({
    queryKey: pluginHistoryKeys.sqlContent(params),
    queryFn: () => fetchSqlContent(params),
    enabled,
    staleTime: 60000, // 1 minute - content doesn't change
  })
}

/**
 * Hook to fetch history clear summary.
 */
export function useHistorySummaryQuery(
  pluginId: string,
  accountId: string,
  enabled = true
) {
  return useQuery({
    queryKey: pluginHistoryKeys.historySummary(pluginId, accountId),
    queryFn: () => fetchHistorySummary(pluginId, accountId),
    enabled,
    staleTime: 10000, // 10 seconds - can change if batches complete
  })
}

/**
 * Mutation hook to download SQL file.
 */
export function useDownloadSqlFile() {
  return useMutation({
    mutationFn: ({
      pluginId,
      accountId,
      generationId,
    }: {
      pluginId: string
      accountId: string
      generationId: string
    }) => downloadSqlFile(pluginId, accountId, generationId),
    onSuccess: (data, variables) => {
      // Create download link
      const blob = new Blob([data], { type: 'text/plain' })
      const url = window.URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `generation-${variables.generationId}.sql`
      document.body.appendChild(link)
      link.click()
      document.body.removeChild(link)
      window.URL.revokeObjectURL(url)
    },
  })
}

/**
 * Mutation hook to clear all history.
 */
export function useClearHistoryMutation() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({
      pluginId,
      accountId,
    }: {
      pluginId: string
      accountId: string
    }) => clearHistory(pluginId, accountId),
    onSuccess: (_, variables) => {
      // Invalidate all queries for this account-plugin
      queryClient.invalidateQueries({
        queryKey: pluginHistoryKeys.generations(variables.pluginId, variables.accountId),
      })
      queryClient.invalidateQueries({
        queryKey: pluginHistoryKeys.historySummary(variables.pluginId, variables.accountId),
      })
    },
  })
}

/**
 * Mutation hook to regenerate SQL.
 */
export function useRegenerateSqlMutation() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({
      pluginId,
      accountId,
      generationId,
    }: {
      pluginId: string
      accountId: string
      generationId: string
    }) => regenerateSql(pluginId, accountId, generationId),
    onSuccess: (_, variables) => {
      // Invalidate generation list to show new entry
      queryClient.invalidateQueries({
        queryKey: pluginHistoryKeys.generations(variables.pluginId, variables.accountId),
      })
      // Invalidate the original generation (now superseded)
      queryClient.invalidateQueries({
        queryKey: pluginHistoryKeys.generation(variables.pluginId, variables.accountId, variables.generationId),
      })
    },
  })
}

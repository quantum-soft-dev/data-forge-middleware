/**
 * TanStack Query hooks for user-facing batch SQL management.
 *
 * @module features/my-plugins/api/batchSqlQueries
 */

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { pluginKeys } from '@/entities/plugin/api/keys'
import {
  fetchBatchesWithSqlStatus,
  generateSql,
  fetchGenerations,
  fetchSqlContent,
  downloadSqlFile,
  regenerateSql,
  deleteGeneration,
} from './batchSqlApi'
import type {
  BatchSqlFilters,
  GenerateSqlRequest,
  SqlContentParams,
} from '../model/types'

/**
 * Hook to fetch batches with SQL generation status.
 */
export function useBatchSqlStatusQuery(filters: BatchSqlFilters) {
  const { pluginId, page = 0, size = 20, siteId } = filters

  return useQuery({
    queryKey: pluginKeys.batchSqlList(pluginId, { page, size, siteId }),
    queryFn: () => fetchBatchesWithSqlStatus(filters),
    enabled: !!pluginId,
    staleTime: 30000, // 30 seconds
  })
}

/**
 * Hook to fetch SQL generations for the current account.
 */
export function useGenerationsQuery(
  pluginId: string,
  page: number = 0,
  includeSuperseded: boolean = false
) {
  return useQuery({
    queryKey: pluginKeys.userGenerationsList(pluginId, page, includeSuperseded),
    queryFn: () => fetchGenerations(pluginId, page, 20, includeSuperseded),
    enabled: !!pluginId,
    staleTime: 30000,
  })
}

/**
 * Hook to fetch SQL content for a generation.
 */
export function useSqlContentQuery(params: SqlContentParams, enabled: boolean = true) {
  return useQuery({
    queryKey: pluginKeys.userSqlContentPage(
      params.pluginId,
      params.generationId,
      params.page ?? 0
    ),
    queryFn: () => fetchSqlContent(params),
    enabled: enabled && !!params.pluginId && !!params.generationId,
    staleTime: 60000, // 1 minute - SQL content doesn't change
  })
}

/**
 * Hook to generate SQL for a batch.
 */
export function useGenerateSqlMutation() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({
      pluginId,
      request,
    }: {
      pluginId: string
      request: GenerateSqlRequest
    }) => generateSql(pluginId, request),
    onSuccess: (_, { pluginId }) => {
      // Invalidate batch SQL status and generations lists
      queryClient.invalidateQueries({ queryKey: pluginKeys.batchSql(pluginId) })
      queryClient.invalidateQueries({ queryKey: pluginKeys.userGenerations(pluginId) })
      // Also invalidate logs as SQL generation creates log entries
      queryClient.invalidateQueries({ queryKey: pluginKeys.pluginLogs(pluginId) })
    },
  })
}

/**
 * Hook to regenerate SQL for a generation.
 */
export function useRegenerateSqlMutation() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({
      pluginId,
      generationId,
    }: {
      pluginId: string
      generationId: string
    }) => regenerateSql(pluginId, generationId),
    onSuccess: (_, { pluginId }) => {
      // Invalidate all related queries
      queryClient.invalidateQueries({ queryKey: pluginKeys.batchSql(pluginId) })
      queryClient.invalidateQueries({ queryKey: pluginKeys.userGenerations(pluginId) })
      queryClient.invalidateQueries({ queryKey: pluginKeys.pluginLogs(pluginId) })
    },
  })
}

/**
 * Hook to delete a SQL generation.
 */
export function useDeleteGenerationMutation() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({
      pluginId,
      generationId,
    }: {
      pluginId: string
      generationId: string
    }) => deleteGeneration(pluginId, generationId),
    onSuccess: (_, { pluginId }) => {
      // Invalidate all related queries
      queryClient.invalidateQueries({ queryKey: pluginKeys.batchSql(pluginId) })
      queryClient.invalidateQueries({ queryKey: pluginKeys.userGenerations(pluginId) })
      queryClient.invalidateQueries({ queryKey: pluginKeys.pluginLogs(pluginId) })
    },
  })
}

/**
 * Hook to download SQL file.
 */
export function useDownloadSqlFileMutation() {
  return useMutation({
    mutationFn: ({
      pluginId,
      generationId,
    }: {
      pluginId: string
      generationId: string
    }) => downloadSqlFile(pluginId, generationId),
  })
}

/**
 * Global Errors TanStack Query Hooks
 *
 * React Query hooks for global error data fetching and mutations.
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { GlobalErrorsQueryParams } from '../model/global-error.types'
import * as api from './global-errors.api'

/**
 * Query keys for global errors.
 */
export const globalErrorsKeys = {
  all: ['global-errors'] as const,
  lists: () => [...globalErrorsKeys.all, 'list'] as const,
  list: (params: GlobalErrorsQueryParams) => [...globalErrorsKeys.lists(), params] as const,
  unreadCount: () => [...globalErrorsKeys.all, 'unread-count'] as const,
  details: () => [...globalErrorsKeys.all, 'detail'] as const,
  detail: (id: string) => [...globalErrorsKeys.details(), id] as const,
}

/**
 * Hook to fetch paginated global errors.
 */
export function useGlobalErrors(params: GlobalErrorsQueryParams = {}) {
  return useQuery({
    queryKey: globalErrorsKeys.list(params),
    queryFn: () => api.listGlobalErrors(params),
  })
}

/**
 * Hook to fetch unread global errors count.
 * Polls every 30 seconds for fresh data.
 */
export function useUnreadCount() {
  return useQuery({
    queryKey: globalErrorsKeys.unreadCount(),
    queryFn: api.getUnreadCount,
    refetchInterval: 30000, // 30 seconds polling
  })
}

/**
 * Hook to fetch single global error details.
 */
export function useGlobalError(errorId: string) {
  return useQuery({
    queryKey: globalErrorsKeys.detail(errorId),
    queryFn: () => api.getGlobalError(errorId),
    enabled: !!errorId,
  })
}

/**
 * Hook to mark single error as read.
 */
export function useMarkAsRead() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (errorId: string) => api.markAsRead(errorId),
    onSuccess: () => {
      // Invalidate all global errors queries
      queryClient.invalidateQueries({ queryKey: globalErrorsKeys.all })
    },
  })
}

/**
 * Hook to mark multiple errors as read.
 */
export function useMarkMultipleAsRead() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (errorIds: string[]) => api.markMultipleAsRead(errorIds),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: globalErrorsKeys.all })
    },
  })
}

/**
 * Hook to mark all errors as read.
 */
export function useMarkAllAsRead() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: () => api.markAllAsRead(),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: globalErrorsKeys.all })
    },
  })
}

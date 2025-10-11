/**
 * useSubscribers Hook
 *
 * React Query hook for fetching account list (subscriber entity).
 * Provides automatic caching, refetching, and loading states.
 */

import { useQuery } from '@tanstack/react-query'
import { subscriberKeys } from './keys'
import { fetchSubscribers } from './client'
import type { SubscriberFilters } from '../model/types'

interface UseSubscribersParams {
  page: number // 1-indexed
  pageSize: number
  search?: string
  status?: 'active' | 'inactive'
}

export function useSubscribers(params: UseSubscribersParams) {
  const filters: SubscriberFilters = {
    page: params.page,
    size: params.pageSize,
    search: params.search,
    status: params.status,
  }

  return useQuery({
    queryKey: subscriberKeys.list(filters),
    queryFn: () => fetchSubscribers(filters),
    staleTime: 30000, // 30 seconds
    gcTime: 300000, // 5 minutes (formerly cacheTime)
  })
}

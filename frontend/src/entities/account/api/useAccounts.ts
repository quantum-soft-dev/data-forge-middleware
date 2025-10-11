/**
 * useAccounts Hook
 *
 * React Query hook for fetching account list.
 * Provides automatic caching, refetching, and loading states.
 */

import { useQuery } from '@tanstack/react-query'
import { accountKeys } from './keys'
import { fetchAccounts } from './client'
import type { AccountFilters } from '../model/types'

interface UseAccountsParams {
  page: number // 1-indexed
  pageSize: number
  search?: string
  status?: 'active' | 'inactive'
}

export function useAccounts(params: UseAccountsParams) {
  const filters: AccountFilters = {
    page: params.page,
    size: params.pageSize,
    search: params.search,
    status: params.status,
  }

  return useQuery({
    queryKey: accountKeys.list(filters),
    queryFn: () => fetchAccounts(filters),
    staleTime: 30000, // 30 seconds
    gcTime: 300000, // 5 minutes (formerly cacheTime)
  })
}

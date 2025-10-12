/**
 * useAccountById Hook
 *
 * React Query hook to fetch a single account by ID.
 * Used for pre-filling edit forms.
 */

import { useQuery } from '@tanstack/react-query'
import { fetchAccountById } from './client'
import { accountKeys } from './keys'

export function useAccountById(id: string) {
  return useQuery({
    queryKey: accountKeys.detail(id),
    queryFn: () => fetchAccountById(id),
    enabled: !!id, // Only fetch if ID is provided
    staleTime: 30_000, // 30 seconds
    gcTime: 5 * 60 * 1000, // 5 minutes
  })
}

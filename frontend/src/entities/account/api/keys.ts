/**
 * React Query Key Factory for Accounts
 *
 * Provides consistent query keys for caching and invalidation.
 * Follows TanStack Query best practices for key management.
 */

import type { AccountFilters } from '../model/types'

export const accountKeys = {
  all: ['accounts'] as const,
  lists: () => [...accountKeys.all, 'list'] as const,
  list: (filters: AccountFilters) => [...accountKeys.lists(), filters] as const,
  details: () => [...accountKeys.all, 'detail'] as const,
  detail: (id: string) => [...accountKeys.details(), id] as const,
}

/**
 * React Query Key Factory for Accounts
 *
 * Provides consistent query keys for caching and invalidation.
 * Follows TanStack Query best practices for key management.
 */

import type { SubscriberFilters } from '../model/types'

export const subscriberKeys = {
  all: ['subscribers'] as const,
  lists: () => [...subscriberKeys.all, 'list'] as const,
  list: (filters: SubscriberFilters) => [...subscriberKeys.lists(), filters] as const,
  details: () => [...subscriberKeys.all, 'detail'] as const,
  detail: (id: string) => [...subscriberKeys.details(), id] as const,
}

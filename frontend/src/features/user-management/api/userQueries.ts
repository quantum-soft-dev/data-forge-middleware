/**
 * TanStack Query hooks for user management read operations.
 *
 * @module features/user-management/api/userQueries
 */

import { useQuery } from '@tanstack/react-query'
import type {
  Account,
  AccountWithKeycloakStatus,
  AdminActionLog,
  AccountListResponse,
  AccountFilters,
} from '../../../entities/account/model/types'

/**
 * Query key factory for account-related queries.
 * Follows TanStack Query best practices for key management.
 */
export const accountKeys = {
  all: ['accounts'] as const,
  lists: () => [...accountKeys.all, 'list'] as const,
  list: (filters: AccountFilters) => [...accountKeys.lists(), filters] as const,
  details: () => [...accountKeys.all, 'detail'] as const,
  detail: (id: string) => [...accountKeys.details(), id] as const,
  auditLogs: (id: string) => [...accountKeys.detail(id), 'audit-logs'] as const,
}

/**
 * Fetch accounts with pagination and filtering.
 */
async function fetchAccounts(filters: AccountFilters): Promise<AccountListResponse> {
  const params = new URLSearchParams()

  if (filters.page !== undefined) {
    params.append('page', String(filters.page - 1)) // Convert 1-indexed UI to 0-indexed API
  }
  if (filters.size) {
    params.append('size', String(filters.size))
  }
  if (filters.search) {
    params.append('search', filters.search)
  }
  if (filters.status) {
    params.append('status', filters.status)
  }

  const response = await fetch(`/api/admin/accounts?${params.toString()}`, {
    headers: {
      'Content-Type': 'application/json',
    },
    credentials: 'include',
  })

  if (!response.ok) {
    throw new Error(`Failed to fetch accounts: ${response.statusText}`)
  }

  return response.json()
}

/**
 * Hook to fetch paginated list of accounts.
 *
 * @param page Page number (1-indexed)
 * @param size Page size
 * @param filters Additional filters (search, status)
 */
export function useAccountsQuery(
  page: number = 1,
  size: number = 20,
  filters: Omit<AccountFilters, 'page' | 'size'> = {}
) {
  return useQuery({
    queryKey: accountKeys.list({ page, size, ...filters }),
    queryFn: () => fetchAccounts({ page, size, ...filters }),
    staleTime: 30000, // 30 seconds
  })
}

/**
 * Fetch single account by ID.
 */
async function fetchAccount(accountId: string): Promise<AccountWithKeycloakStatus> {
  const response = await fetch(`/api/admin/accounts/${accountId}`, {
    headers: {
      'Content-Type': 'application/json',
    },
    credentials: 'include',
  })

  if (!response.ok) {
    throw new Error(`Failed to fetch account: ${response.statusText}`)
  }

  return response.json()
}

/**
 * Hook to fetch single account details.
 *
 * @param accountId Account UUID
 */
export function useAccountQuery(accountId: string) {
  return useQuery({
    queryKey: accountKeys.detail(accountId),
    queryFn: () => fetchAccount(accountId),
    enabled: !!accountId, // Only run if accountId is provided
    staleTime: 30000,
  })
}

/**
 * Fetch audit logs for an account.
 */
async function fetchAccountAuditLogs(accountId: string): Promise<AdminActionLog[]> {
  const response = await fetch(`/api/admin/accounts/${accountId}/audit-logs`, {
    headers: {
      'Content-Type': 'application/json',
    },
    credentials: 'include',
  })

  if (!response.ok) {
    throw new Error(`Failed to fetch audit logs: ${response.statusText}`)
  }

  return response.json()
}

/**
 * Hook to fetch audit logs for an account.
 *
 * @param accountId Account UUID
 */
export function useAccountAuditLogsQuery(accountId: string) {
  return useQuery({
    queryKey: accountKeys.auditLogs(accountId),
    queryFn: () => fetchAccountAuditLogs(accountId),
    enabled: !!accountId,
    staleTime: 10000, // 10 seconds (audit logs should be relatively fresh)
  })
}

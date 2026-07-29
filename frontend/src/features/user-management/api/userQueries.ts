/**
 * TanStack Query hooks for user management read operations.
 *
 * @module features/user-management/api/userQueries
 */

import { useQuery } from '@tanstack/react-query'
import { apiClient } from '@/shared/api/client'
import { ACCOUNTS, ACCOUNTS_ID, ACCOUNTS_AUDIT_LOGS } from '@/shared/api/apiRoutes'
import type {
  AccountWithAuthStatus,
  AdminActionLogListResponse,
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
  auditLogs: (id: string, page: number, size: number) => [...accountKeys.detail(id), 'audit-logs', page, size] as const,
}

/**
 * Fetch accounts with Auth0 integration data (pagination and filtering).
 * Returns accounts with lastLogin and Auth0 status information.
 */
async function fetchAccounts(filters: AccountFilters): Promise<{
  content: AccountWithAuthStatus[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}> {
  const params = new URLSearchParams()

  if (filters.page !== undefined) {
    params.append('page', String(filters.page)) // Already 0-indexed from caller
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

  const response = await apiClient.get<{
    content: AccountWithAuthStatus[]
    page: number
    size: number
    totalElements: number
    totalPages: number
  }>(`${ACCOUNTS}?${params.toString()}`)

  return response.data
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
 * Fetch single account by ID with Auth0 integration data.
 * Uses the standard accounts endpoint to get full Auth0 status.
 */
async function fetchAccount(accountId: string): Promise<AccountWithAuthStatus> {
  const response = await apiClient.get<AccountWithAuthStatus>(
    ACCOUNTS_ID(accountId)
  )

  return response.data
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
 * Fetch audit logs for an account with pagination.
 */
async function fetchAccountAuditLogs(
  accountId: string,
  page: number = 0,
  size: number = 20,
  sort: string = 'createdAt,desc'
): Promise<AdminActionLogListResponse> {
  const params = new URLSearchParams()
  params.append('page', String(page))
  params.append('size', String(size))
  params.append('sort', sort)

  const response = await apiClient.get<AdminActionLogListResponse>(
    `${ACCOUNTS_AUDIT_LOGS(accountId)}?${params.toString()}`
  )

  return response.data
}

/**
 * Hook to fetch paginated audit logs for an account.
 *
 * @param accountId Account UUID
 * @param page Page number (0-indexed to match API)
 * @param size Page size (default: 20)
 */
export function useAccountAuditLogsQuery(
  accountId: string,
  page: number = 0,
  size: number = 20
) {
  return useQuery({
    queryKey: accountKeys.auditLogs(accountId, page, size),
    queryFn: () => fetchAccountAuditLogs(accountId, page, size),
    enabled: !!accountId,
    staleTime: 10000, // 10 seconds (audit logs should be relatively fresh)
  })
}

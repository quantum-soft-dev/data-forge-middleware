/**
 * Account API Client (Account entity)
 *
 * HTTP client for account endpoints.
 * Uses axios instance from shared/api/client.
 * Maps backend Account model to frontend Account interface.
 */

import { apiClient } from '@/shared/api/client'
import { ACCOUNTS, ACCOUNTS_ID } from '@/shared/api/apiRoutes'
import type { AccountFilters, AccountListResponse, Account } from '../model/types'
import type { CreateAccountFormData, UpdateAccountFormData } from '../model/schema'

/**
 * Fetch accounts with pagination and filtering
 * Backend endpoint: GET /api/v1/accounts
 *
 * @param filters - Search, status, and pagination params
 * @returns Paginated list of accounts (mapped to Account interface)
 */
export async function fetchAccounts(
  filters: AccountFilters
): Promise<AccountListResponse> {
  const params = new URLSearchParams()

  // Convert 1-indexed page to 0-indexed for API
  if (filters.page !== undefined) {
    params.append('page', String(filters.page - 1))
  }

  if (filters.size !== undefined) {
    params.append('size', String(filters.size))
  }

  if (filters.search) {
    params.append('search', filters.search)
  }

  if (filters.status) {
    params.append('status', filters.status)
  }

  const response = await apiClient.get<AccountListResponse>(
    `${ACCOUNTS}?${params.toString()}`
  )

  return response.data
}

/**
 * Fetch a single account by ID
 * Backend endpoint: GET /api/v1/accounts/{id}
 *
 * @param id - Account ID
 * @returns Account details
 */
export async function fetchAccountById(id: string): Promise<Account> {
  const response = await apiClient.get<Account>(ACCOUNTS_ID(id))
  return response.data
}

/**
 * Create a new account
 * Backend endpoint: POST /api/v1/accounts
 *
 * @param data - Account data (name, email)
 * @returns Created account with ID and metadata (mapped to Account interface)
 */
export async function createAccount(
  data: CreateAccountFormData
): Promise<Account> {
  const response = await apiClient.post<Account>(ACCOUNTS, data)
  return response.data
}

/**
 * Update an existing account
 * Backend endpoint: PUT /api/v1/accounts/{id}
 *
 * @param id - Account ID
 * @param data - Updated account data (partial)
 * @returns Updated account
 */
export async function updateAccount(
  id: string,
  data: Omit<UpdateAccountFormData, 'id'>
): Promise<Account> {
  const response = await apiClient.put<Account>(ACCOUNTS_ID(id), data)
  return response.data
}

/**
 * Delete an account (soft delete - sets status to inactive)
 * Backend endpoint: DELETE /api/v1/accounts/{id}
 *
 * @param id - Account ID
 */
export async function deleteAccount(id: string): Promise<void> {
  await apiClient.delete(ACCOUNTS_ID(id))
}

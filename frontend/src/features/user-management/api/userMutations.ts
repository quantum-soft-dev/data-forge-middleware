/**
 * TanStack Query hooks for user management write operations.
 *
 * @module features/user-management/api/userMutations
 */

import { useMutation, useQueryClient } from '@tanstack/react-query'
import type {
  CreateAccountRequest,
  CreateAccountResponse,
} from '../../../entities/account/model/types'
import { accountKeys } from './userQueries'

/**
 * Create account with Keycloak integration.
 */
async function createAccount(request: CreateAccountRequest): Promise<CreateAccountResponse> {
  const response = await fetch('/api/admin/accounts/with-keycloak', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    credentials: 'include',
    body: JSON.stringify(request),
  })

  if (!response.ok) {
    const error = await response.json()
    throw new Error(error.message || 'Failed to create account')
  }

  return response.json()
}

/**
 * Hook to create account with Keycloak.
 * Automatically invalidates account list queries on success.
 */
export function useCreateAccountMutation() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: createAccount,
    onSuccess: () => {
      // Invalidate all account list queries to refetch fresh data
      queryClient.invalidateQueries({ queryKey: accountKeys.lists() })
    },
  })
}

/**
 * Lock account (disable Keycloak user).
 */
async function lockAccount(accountId: string): Promise<void> {
  const response = await fetch(`/api/admin/accounts/${accountId}/lock`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    credentials: 'include',
  })

  if (!response.ok) {
    const error = await response.json()
    throw new Error(error.message || 'Failed to lock account')
  }
}

/**
 * Hook to lock account.
 * Invalidates specific account detail query on success.
 */
export function useLockAccountMutation() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: lockAccount,
    onSuccess: (_, accountId) => {
      // Invalidate specific account detail to refetch
      queryClient.invalidateQueries({ queryKey: accountKeys.detail(accountId) })
    },
  })
}

/**
 * Unlock account (enable Keycloak user).
 */
async function unlockAccount(accountId: string): Promise<void> {
  const response = await fetch(`/api/admin/accounts/${accountId}/unlock`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    credentials: 'include',
  })

  if (!response.ok) {
    const error = await response.json()
    throw new Error(error.message || 'Failed to unlock account')
  }
}

/**
 * Hook to unlock account.
 * Invalidates specific account detail query on success.
 */
export function useUnlockAccountMutation() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: unlockAccount,
    onSuccess: (_, accountId) => {
      // Invalidate specific account detail to refetch
      queryClient.invalidateQueries({ queryKey: accountKeys.detail(accountId) })
    },
  })
}

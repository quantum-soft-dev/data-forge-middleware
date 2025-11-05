/**
 * TanStack Query hooks for user management write operations.
 *
 * @module features/user-management/api/userMutations
 */

import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { apiClient } from '@/shared/api/client'
import type {
  CreateAccountRequest,
  CreateAccountResponse,
  ResetPasswordResponse,
} from '../../../entities/account/model/types'
import { accountKeys } from './userQueries'

/**
 * Create account with Keycloak integration.
 */
async function createAccount(request: CreateAccountRequest): Promise<CreateAccountResponse> {
  try {
    const response = await apiClient.post<CreateAccountResponse>(
      '/admin/accounts/with-keycloak',
      request
    )
    return response.data
  } catch (error: any) {
    const errorMessage = error.response?.data?.message || 'Failed to create account'
    const statusCode = error.response?.status || 'unknown'
    throw new Error(`${errorMessage} (HTTP ${statusCode})`)
  }
}

/**
 * Hook to create account with Keycloak.
 * Automatically invalidates account list queries on success.
 */
export function useCreateAccountMutation() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: createAccount,
    onSuccess: (data) => {
      // Invalidate all account list queries to refetch fresh data
      queryClient.invalidateQueries({ queryKey: accountKeys.lists() })
      // Show success toast
      toast.success('Account created successfully', {
        description: `Account for ${data.account.email} has been created with temporary password.`,
      })
    },
    onError: (error) => {
      // Show error toast
      toast.error('Failed to create account', {
        description: error.message,
      })
    },
  })
}

/**
 * Lock account (disable Keycloak user).
 */
async function lockAccount(accountId: string): Promise<void> {
  try {
    await apiClient.post(`/admin/accounts/${accountId}/lock`)
  } catch (error: any) {
    const errorMessage = error.response?.data?.message || 'Failed to lock account'
    const statusCode = error.response?.status || 'unknown'
    throw new Error(`${errorMessage} (HTTP ${statusCode})`)
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
      // Show success toast
      toast.success('Account locked successfully', {
        description: 'The account has been disabled in Keycloak.',
      })
    },
    onError: (error) => {
      // Show error toast
      toast.error('Failed to lock account', {
        description: error.message,
      })
    },
  })
}

/**
 * Unlock account (enable Keycloak user).
 */
async function unlockAccount(accountId: string): Promise<void> {
  try {
    await apiClient.post(`/admin/accounts/${accountId}/unlock`)
  } catch (error: any) {
    const errorMessage = error.response?.data?.message || 'Failed to unlock account'
    const statusCode = error.response?.status || 'unknown'
    throw new Error(`${errorMessage} (HTTP ${statusCode})`)
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
      // Show success toast
      toast.success('Account unlocked successfully', {
        description: 'The account has been enabled in Keycloak.',
      })
    },
    onError: (error) => {
      // Show error toast
      toast.error('Failed to unlock account', {
        description: error.message,
      })
    },
  })
}

/**
 * Reset account password to new temporary password.
 */
async function resetPassword(accountId: string): Promise<ResetPasswordResponse> {
  try {
    const response = await apiClient.post<ResetPasswordResponse>(
      `/admin/accounts/${accountId}/reset-password`
    )
    return response.data
  } catch (error: any) {
    const errorMessage = error.response?.data?.message || 'Failed to reset password'
    const statusCode = error.response?.status || 'unknown'
    throw new Error(`${errorMessage} (HTTP ${statusCode})`)
  }
}

/**
 * Hook to reset account password.
 * Invalidates specific account detail query on success.
 * Returns temporary password and expiration in response.
 */
export function useResetPasswordMutation() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: resetPassword,
    onSuccess: (_data, accountId) => {
      // Invalidate specific account detail to refetch
      queryClient.invalidateQueries({ queryKey: accountKeys.detail(accountId) })
      // Show success toast
      toast.success('Password reset successfully', {
        description: 'A temporary password has been generated. Make sure to save it.',
      })
    },
    onError: (error) => {
      // Show error toast
      toast.error('Failed to reset password', {
        description: error.message,
      })
    },
  })
}

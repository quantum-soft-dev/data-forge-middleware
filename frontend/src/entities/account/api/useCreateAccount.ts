/**
 * useCreateAccount Hook
 *
 * React Query mutation hook for creating new accounts.
 * Invalidates account list queries on success.
 */

import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { createAccount } from './client'
import { accountKeys } from './keys'
import type { CreateAccountFormData } from '../model/schema'
import { AxiosError } from 'axios'

interface ErrorResponse {
  message?: string
  status?: number
}

/**
 * Hook for creating a new account
 *
 * Features:
 * - Invalidates account list queries on success
 * - Shows success toast notification
 * - Handles 409 Conflict (duplicate email) errors
 * - Handles 400 Bad Request (validation) errors
 *
 * @returns Mutation object with mutate, isPending, isSuccess, error
 */
export function useCreateAccount() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (data: CreateAccountFormData) => createAccount(data),
    onSuccess: () => {
      // Invalidate all account list queries to refetch with new data
      queryClient.invalidateQueries({
        queryKey: accountKeys.lists(),
      })

      toast.success('Account created successfully')
    },
    onError: (error: unknown) => {
      if (error instanceof AxiosError) {
        const status = error.response?.status
        const message = (error.response?.data as ErrorResponse)?.message

        if (status === 409) {
          toast.error('Email already exists')
        } else if (status === 400) {
          toast.error(message || 'Validation error. Please check your input.')
        } else {
          toast.error('Failed to create account. Please try again.')
        }
      } else {
        toast.error('An unexpected error occurred')
      }
    },
  })
}

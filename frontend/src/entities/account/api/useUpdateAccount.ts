/**
 * useUpdateAccount Hook
 *
 * React Query mutation hook for updating accounts.
 * Includes optimistic updates and cache invalidation.
 */

import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { updateAccount } from './client'
import { accountKeys } from './keys'
import type { UpdateAccountFormData } from '../model/schema'
import { getServerErrorMessage, getServerErrorStatus } from '@/shared/api/error-handler'

/** Per-field validation errors the backend attaches to a 400 body. */
function getFieldErrors(error: unknown): Record<string, string> | undefined {
  if (typeof error !== 'object' || error === null || !('response' in error)) {
    return undefined
  }
  const data = (error as { response?: { data?: { fieldErrors?: Record<string, string> } } })
    .response?.data
  return data?.fieldErrors
}

export function useUpdateAccount() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ id, ...data }: UpdateAccountFormData) => updateAccount(id, data),

    onSuccess: (updatedAccount) => {
      // Invalidate and refetch account list queries
      queryClient.invalidateQueries({ queryKey: accountKeys.lists() })

      // Invalidate specific account detail query
      queryClient.invalidateQueries({ queryKey: accountKeys.detail(updatedAccount.id) })

      // Show success toast
      toast.success('Account updated successfully')
    },

    onError: (error: unknown) => {
      // Handle specific error codes
      const status = getServerErrorStatus(error)
      if (status === 409) {
        toast.error('Email already exists')
      } else if (status === 404) {
        toast.error('Account not found')
      } else if (status === 400) {
        const fieldErrors = getFieldErrors(error)
        if (fieldErrors) {
          // Show first field error
          const firstError = Object.values(fieldErrors)[0]
          toast.error(firstError)
        } else {
          toast.error(getServerErrorMessage(error) ?? 'Failed to update account')
        }
      } else {
        toast.error(
          (error instanceof Error ? error.message : undefined) || 'Failed to update account'
        )
      }
    },
  })
}

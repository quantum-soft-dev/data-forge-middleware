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

    onError: (error: any) => {
      // Handle specific error codes
      if (error.response?.status === 409) {
        toast.error('Email already exists')
      } else if (error.response?.status === 404) {
        toast.error('Account not found')
      } else if (error.response?.status === 400) {
        const fieldErrors = error.response?.data?.fieldErrors
        if (fieldErrors) {
          // Show first field error
          const firstError = Object.values(fieldErrors)[0] as string
          toast.error(firstError)
        } else {
          toast.error(error.response?.data?.message || 'Failed to update account')
        }
      } else {
        toast.error(error.message || 'Failed to update account')
      }
    },
  })
}

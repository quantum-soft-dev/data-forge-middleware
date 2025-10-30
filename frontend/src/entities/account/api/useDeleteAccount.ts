/**
 * useDeleteAccount Hook
 *
 * React Query mutation hook for deleting accounts (soft delete).
 * Includes optimistic updates and cache invalidation.
 */

import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { deleteAccount } from './client'
import { accountKeys } from './keys'

export function useDeleteAccount() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (id: string) => deleteAccount(id),

    onSuccess: (_data, deletedId) => {
      // Invalidate and refetch account list queries
      queryClient.invalidateQueries({ queryKey: accountKeys.lists() })

      // Remove from detail cache
      queryClient.removeQueries({ queryKey: accountKeys.detail(deletedId) })

      // Show success toast
      toast.success('Account deleted successfully')
    },

    onError: (error: any) => {
      // Handle specific error codes
      if (error.response?.status === 404) {
        toast.error('Account not found')
      } else {
        toast.error(error.message || 'Failed to delete account')
      }
    },
  })
}

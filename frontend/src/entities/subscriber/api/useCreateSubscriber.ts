/**
 * useCreateSubscriber Hook
 *
 * React Query mutation hook for creating new subscribers.
 * Invalidates subscriber list queries on success.
 */

import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { createSubscriber } from './client'
import { subscriberKeys } from './keys'
import type { CreateSubscriberFormData } from '../model/schema'
import { AxiosError } from 'axios'

interface ErrorResponse {
  message?: string
  status?: number
}

/**
 * Hook for creating a new subscriber
 *
 * Features:
 * - Invalidates subscriber list queries on success
 * - Shows success toast notification
 * - Handles 409 Conflict (duplicate email) errors
 * - Handles 400 Bad Request (validation) errors
 *
 * @returns Mutation object with mutate, isPending, isSuccess, error
 */
export function useCreateSubscriber() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (data: CreateSubscriberFormData) => createSubscriber(data),
    onSuccess: () => {
      // Invalidate all subscriber list queries to refetch with new data
      queryClient.invalidateQueries({
        queryKey: subscriberKeys.lists(),
      })

      toast.success('Subscriber created successfully')
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
          toast.error('Failed to create subscriber. Please try again.')
        }
      } else {
        toast.error('An unexpected error occurred')
      }
    },
  })
}

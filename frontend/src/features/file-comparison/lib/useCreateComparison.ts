/**
 * TanStack Query hook for creating file comparisons
 *
 * Feature: 009-markdown-user-story (User Story 1)
 */

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { createComparison } from '@/entities/comparison/api/comparisonApi';
import type { CreateComparisonRequest, ComparisonResponse } from '@/entities/comparison/model/types';

interface UseCreateComparisonOptions {
  onSuccess?: (data: ComparisonResponse) => void;
  onError?: (error: Error) => void;
}

/**
 * Hook for creating a new file comparison
 */
export function useCreateComparison(options?: UseCreateComparisonOptions) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (request: CreateComparisonRequest) => createComparison(request),
    onSuccess: (data) => {
      // Invalidate comparisons list to refetch
      queryClient.invalidateQueries({ queryKey: ['comparisons'] });
      options?.onSuccess?.(data);
    },
    onError: (error: Error) => {
      options?.onError?.(error);
    },
  });
}

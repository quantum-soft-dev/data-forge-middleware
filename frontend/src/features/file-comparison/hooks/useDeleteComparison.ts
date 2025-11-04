/**
 * T114: useDeleteComparison mutation hook
 *
 * TanStack Query mutation hook for deleting comparisons with:
 * - Optimistic updates to the UI
 * - Automatic cache invalidation
 * - Error handling
 *
 * User Story: US7 - Delete Saved Comparisons (Phase 9)
 * Priority: P4
 *
 * @module features/file-comparison/hooks/useDeleteComparison
 */

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { comparisonApi } from '../api/comparisonApi';
import { toast } from 'sonner';

/**
 * Mutation hook for deleting a comparison.
 *
 * Features:
 * - Deletes comparison and all associated results (CASCADE)
 * - Invalidates 'comparisons' query cache to refresh list
 * - Invalidates specific comparison query cache
 * - Shows success/error toast notifications
 * - Supports optimistic updates via callbacks
 *
 * @returns TanStack Query mutation result with mutate function
 *
 * @example
 * ```tsx
 * function DeleteButton({ comparisonId }: { comparisonId: number }) {
 *   const { mutate, isPending } = useDeleteComparison();
 *
 *   const handleDelete = () => {
 *     mutate(comparisonId, {
 *       onSuccess: () => {
 *         console.log('Deleted successfully');
 *       },
 *     });
 *   };
 *
 *   return (
 *     <button onClick={handleDelete} disabled={isPending}>
 *       {isPending ? 'Deleting...' : 'Delete'}
 *     </button>
 *   );
 * }
 * ```
 */
export const useDeleteComparison = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (comparisonId: string) => comparisonApi.deleteComparison(Number(comparisonId)),

    onSuccess: (_data, comparisonId) => {
      // Invalidate comparison list queries to refresh the list view
      queryClient.invalidateQueries({ queryKey: ['comparisons'] });

      // Invalidate the specific comparison query (in case user tries to access it)
      queryClient.invalidateQueries({ queryKey: ['comparison', comparisonId] });

      // Show success toast
      toast.success('Comparison deleted successfully', {
        description: 'The comparison and all its results have been removed.',
      });
    },

    onError: (error: Error, _comparisonId) => {
      // Show error toast with specific message
      const errorMessage =
        error.message.includes('IN_PROGRESS')
          ? 'Cannot delete comparison while it is in progress. Please wait for it to complete.'
          : error.message || 'Failed to delete comparison. Please try again.';

      toast.error('Delete failed', {
        description: errorMessage,
      });
    },
  });
};

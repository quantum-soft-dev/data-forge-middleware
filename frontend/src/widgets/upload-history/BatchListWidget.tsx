/**
 * T038: Batch list widget - container component
 *
 * Connects BatchListView to React Query data layer.
 * Handles data fetching, error states, and infinite scroll.
 *
 * Feature: 008-upload-history-user (User Story 1)
 */

import { useNavigate } from '@tanstack/react-router';
import { useBatchHistory } from '@/entities/batch/api/queries';
import { BatchListView } from '@/features/upload-history/ui/BatchListView';

/**
 * T038: Batch list widget with data fetching
 */
export function BatchListWidget() {
  const navigate = useNavigate();
  const {
    data,
    isLoading,
    error,
    hasNextPage,
    isFetchingNextPage,
    fetchNextPage,
  } = useBatchHistory(20);

  // Flatten all pages into single array
  const batches = data?.pages.flatMap((page) => page.items) ?? [];

  /**
   * T057: Navigate to batch detail page
   */
  const handleBatchClick = (batchId: string) => {
    navigate({ to: '/account/upload-history/$batchId', params: { batchId } });
  };

  return (
    <BatchListView
      batches={batches}
      isLoading={isLoading}
      hasNextPage={hasNextPage ?? false}
      isFetchingNextPage={isFetchingNextPage}
      onLoadMore={fetchNextPage}
      error={error?.message ?? null}
      onBatchClick={handleBatchClick}
    />
  );
}

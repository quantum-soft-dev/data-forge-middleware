/**
 * T038: Batch list widget - container component
 *
 * Connects BatchListView to React Query data layer.
 * Handles data fetching, error states, and infinite scroll.
 *
 * Feature: 008-upload-history-user (User Story 1)
 */

import { useState, useCallback, useMemo } from 'react';
import { useNavigate } from '@tanstack/react-router';
import { useBatchHistory } from '@/entities/batch/api/queries';
import { useSites } from '@/features/site-crud/model/queries';
import { BatchListView } from '@/features/upload-history/ui/BatchListView';

interface BatchListWidgetProps {
  /**
   * Lock the list to one site (023, F3 — site-detail "Upload history" tab).
   * Hides the site filter dropdown and shows only this site's batches.
   */
  siteId?: string;
}

/**
 * T038: Batch list widget with data fetching
 */
export function BatchListWidget({ siteId }: BatchListWidgetProps = {}) {
  const navigate = useNavigate();
  const [isRefreshing, setIsRefreshing] = useState(false);
  const {
    data,
    isLoading,
    error,
    hasNextPage,
    isFetchingNextPage,
    fetchNextPage,
    refetch,
  } = useBatchHistory(20);
  const { data: sites } = useSites();

  // Flatten all pages into single array; when locked to a site, keep only its batches
  const allBatches = data?.pages.flatMap((page) => page.items) ?? [];
  const batches = siteId ? allBatches.filter((batch) => batch.siteId === siteId) : allBatches;

  // Create site ID to name lookup map for displaying site names
  const siteLookup = useMemo(() => {
    if (!sites) return new Map<string, string>();
    return new Map(sites.map(site => [site.id, site.name]));
  }, [sites]);

  /**
   * T057: Navigate to batch detail page
   */
  const handleBatchClick = (batchId: string) => {
    navigate({ to: '/account/upload-history/$batchId', params: { batchId } });
  };

  /**
   * Refresh the batch list
   */
  const handleRefresh = useCallback(async () => {
    setIsRefreshing(true);
    try {
      await refetch();
    } finally {
      setIsRefreshing(false);
    }
  }, [refetch]);

  return (
    <BatchListView
      batches={batches}
      isLoading={isLoading}
      hasNextPage={hasNextPage ?? false}
      isFetchingNextPage={isFetchingNextPage}
      onLoadMore={fetchNextPage}
      error={error?.message ?? null}
      onBatchClick={handleBatchClick}
      sites={siteId ? undefined : sites}
      siteLookup={siteLookup}
      onRefresh={handleRefresh}
      isRefreshing={isRefreshing}
    />
  );
}

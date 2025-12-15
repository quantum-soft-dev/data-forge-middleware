/**
 * T056: Batch detail widget - container component
 *
 * Connects BatchDetailView to React Query data layer.
 * Handles data fetching, error states, and file selection state.
 *
 * Feature: 008-upload-history-user (User Story 2)
 */

import { useMemo } from 'react';
import { useBatchDetails } from '@/entities/batch/api/queries';
import { useSites } from '@/features/site-crud/model/queries';
import { BatchDetailView } from '@/features/upload-history/ui/BatchDetailView';

interface BatchDetailWidgetProps {
  /** Batch ID to load */
  batchId: string;
  /** Callback when user navigates back to list */
  onBack?: () => void;
}

/**
 * T056: Batch detail widget with data fetching
 */
export function BatchDetailWidget({ batchId, onBack }: BatchDetailWidgetProps) {
  const { data: batch, isLoading, error } = useBatchDetails(batchId);
  const { data: sites } = useSites();

  // Create site lookup map for displaying site name
  const siteName = useMemo(() => {
    if (!batch?.siteId || !sites) return undefined;
    const site = sites.find((s) => s.id === batch.siteId);
    return site?.name;
  }, [batch?.siteId, sites]);

  return (
    <BatchDetailView
      batch={batch}
      siteName={siteName}
      isLoading={isLoading}
      error={error?.message ?? null}
      onBack={onBack}
    />
  );
}

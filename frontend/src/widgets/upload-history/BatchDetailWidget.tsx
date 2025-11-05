/**
 * T056: Batch detail widget - container component
 *
 * Connects BatchDetailView to React Query data layer.
 * Handles data fetching, error states, and file selection state.
 *
 * Feature: 008-upload-history-user (User Story 2)
 */

import { useBatchDetails } from '@/entities/batch/api/queries';
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

  return (
    <BatchDetailView
      batch={batch}
      isLoading={isLoading}
      error={error?.message ?? null}
      onBack={onBack}
    />
  );
}

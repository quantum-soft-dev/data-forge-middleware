/**
 * Compare button component for batch detail page
 *
 * Allows users to initiate file comparison from the current batch.
 * Navigates to comparison creation page with pre-selected batch.
 *
 * Feature: 009-markdown-user-story (User Story 1/2 integration)
 */

import { useNavigate } from '@tanstack/react-router';
import { GitCompare } from 'lucide-react';
import { Button } from '@/shared/ui/ui/button';

interface CompareButtonProps {
  /** Current batch ID to compare from */
  batchId: string;
  /** Selected file IDs (optional - can select in comparison page) */
  selectedFileIds: string[];
  /** Batch status (only allow comparison for COMPLETED batches) */
  batchStatus: string;
}

/**
 * Button to initiate file comparison from batch detail page
 */
export function CompareButton({ batchId, selectedFileIds, batchStatus }: CompareButtonProps) {
  const navigate = useNavigate();

  const handleCompare = () => {
    // Navigate to comparison creation page with current batch pre-selected
    navigate({
      to: '/account/comparisons/create',
      search: {
        currentBatchId: batchId,
        fileIds: selectedFileIds.length > 0 ? selectedFileIds.join(',') : undefined,
      },
    });
  };

  // Only enable for completed batches
  const isDisabled = batchStatus !== 'COMPLETED';

  return (
    <Button
      onClick={handleCompare}
      disabled={isDisabled}
      variant="outline"
      size="sm"
      className="gap-2"
    >
      <GitCompare className="h-4 w-4" />
      Compare Files
      {selectedFileIds.length > 0 && (
        <span className="ml-1 rounded-full bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-800">
          {selectedFileIds.length}
        </span>
      )}
    </Button>
  );
}

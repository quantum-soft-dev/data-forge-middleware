/**
 * Compare button component for batch detail page
 *
 * Opens a modal dialog to select target batch for comparison.
 * No separate page navigation - everything happens inline.
 *
 * Feature: 009-markdown-user-story (User Story 1/2 integration)
 */

import { useState } from 'react';
import { GitCompare } from 'lucide-react';
import { Button } from '@/shared/ui/ui/button';
import { CompareFilesModal } from './CompareFilesModal';

interface CompareButtonProps {
  /** Current batch ID to compare from */
  batchId: string;
  /** Site ID for filtering target batches (only same-site comparisons) */
  siteId: string;
  /** Selected file IDs (optional - can select in comparison page) */
  selectedFileIds: string[];
  /** Batch status (not used - any batch with files can be compared) */
  batchStatus: string;
}

/**
 * Button to initiate file comparison from batch detail page
 */
export function CompareButton({ batchId, siteId, selectedFileIds }: CompareButtonProps) {
  const [isModalOpen, setIsModalOpen] = useState(false);

  // Disabled only if no files selected
  const isDisabled = selectedFileIds.length === 0;

  return (
    <>
      <Button
        onClick={() => setIsModalOpen(true)}
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

      <CompareFilesModal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        currentBatchId={batchId}
        currentSiteId={siteId}
        selectedFileIds={selectedFileIds}
      />
    </>
  );
}

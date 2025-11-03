/**
 * Modal dialog for selecting target batch to compare files
 *
 * Shows inline dialog without navigation to separate page.
 * Allows user to select target batch from list of all batches.
 *
 * Feature: 009-markdown-user-story (User Story 1/2 integration)
 */

import { useState } from 'react';
import { useBatchHistory } from '@/entities/batch/api/queries';
import { useCreateComparison } from '@/features/file-comparison/lib/useCreateComparison';
import { toast } from 'sonner';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/shared/ui/ui/alert-dialog';
import { Button } from '@/shared/ui/ui/button';
import { Loader2 } from 'lucide-react';
import { formatDateTime } from '@/shared/lib/formatters';

interface CompareFilesModalProps {
  /** Is modal open */
  isOpen: boolean;
  /** Close modal callback */
  onClose: () => void;
  /** Current batch ID (source) */
  currentBatchId: string;
  /** Selected file IDs to compare */
  selectedFileIds: string[];
}

/**
 * Modal dialog for target batch selection and comparison creation
 */
export function CompareFilesModal({
  isOpen,
  onClose,
  currentBatchId,
  selectedFileIds,
}: CompareFilesModalProps) {
  const [selectedTargetBatchId, setSelectedTargetBatchId] = useState<string>('');

  // Fetch available batches for comparison
  const { data: batchesData, isLoading: isBatchesLoading } = useBatchHistory(50);

  // Create comparison mutation
  const createComparisonMutation = useCreateComparison({
    onSuccess: (data) => {
      toast.success('Comparison created successfully!', {
        description: `Comparing ${selectedFileIds.length} files between batches`,
      });
      onClose();
      setSelectedTargetBatchId('');
    },
    onError: (error) => {
      toast.error('Failed to create comparison', {
        description: error.message,
      });
    },
  });

  const handleCreate = () => {
    if (!selectedTargetBatchId) return;

    createComparisonMutation.mutate({
      currentBatchId,
      targetBatchId: selectedTargetBatchId,
      fileIds: selectedFileIds.length > 0 ? selectedFileIds : null,
    });
  };

  // Filter out current batch from available batches
  const allBatches = batchesData?.pages.flatMap(page => page.items) ?? [];
  const availableBatches = allBatches.filter((batch) => batch.id !== currentBatchId);

  return (
    <AlertDialog open={isOpen} onOpenChange={onClose}>
      <AlertDialogContent className="max-w-2xl max-h-[80vh] overflow-y-auto">
        <AlertDialogHeader>
          <AlertDialogTitle>Select Batch to Compare</AlertDialogTitle>
          <AlertDialogDescription>
            Choose a target batch to compare your selected files against. {selectedFileIds.length}{' '}
            file(s) will be compared.
          </AlertDialogDescription>
        </AlertDialogHeader>

        <div className="space-y-2">
          {isBatchesLoading ? (
            <div className="flex items-center justify-center py-8">
              <Loader2 className="h-6 w-6 animate-spin text-gray-400" />
              <span className="ml-2 text-sm text-gray-600">Loading batches...</span>
            </div>
          ) : availableBatches.length === 0 ? (
            <div className="py-8 text-center">
              <p className="text-sm text-gray-600">No other batches available for comparison.</p>
            </div>
          ) : (
            availableBatches.map((batch) => (
              <button
                key={batch.id}
                onClick={() => setSelectedTargetBatchId(batch.id)}
                className={`w-full rounded-lg border p-4 text-left transition-colors ${
                  selectedTargetBatchId === batch.id
                    ? 'border-blue-500 bg-blue-50'
                    : 'border-gray-200 hover:border-gray-300 hover:bg-gray-50'
                }`}
              >
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-medium text-gray-900">
                        {formatDateTime(batch.startedAt)}
                      </span>
                      <span
                        className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${
                          batch.status === 'COMPLETED'
                            ? 'bg-green-100 text-green-800'
                            : batch.status === 'IN_PROGRESS'
                            ? 'bg-blue-100 text-blue-800'
                            : 'bg-gray-100 text-gray-800'
                        }`}
                      >
                        {batch.status}
                      </span>
                    </div>
                    <p className="mt-1 text-xs text-gray-500">
                      {batch.fileCount} files • {Math.round(batch.totalSize / 1024)} KB
                    </p>
                  </div>
                  {selectedTargetBatchId === batch.id && (
                    <div className="ml-2 flex h-5 w-5 items-center justify-center rounded-full bg-blue-500">
                      <svg
                        className="h-3 w-3 text-white"
                        fill="none"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth="2"
                        viewBox="0 0 24 24"
                        stroke="currentColor"
                      >
                        <path d="M5 13l4 4L19 7" />
                      </svg>
                    </div>
                  )}
                </div>
              </button>
            ))
          )}
        </div>

        <AlertDialogFooter>
          <AlertDialogCancel disabled={createComparisonMutation.isPending}>
            Cancel
          </AlertDialogCancel>
          <Button
            onClick={handleCreate}
            disabled={!selectedTargetBatchId || createComparisonMutation.isPending}
          >
            {createComparisonMutation.isPending ? (
              <>
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                Creating...
              </>
            ) : (
              'Create Comparison'
            )}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

/**
 * T044: Comparison creation page
 *
 * Integrates FileTable (reused from Upload History) with batch selection
 * and form validation for creating file comparisons.
 *
 * Feature: 009-markdown-user-story (User Story 1)
 */

import { useState, useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useNavigate, useSearch } from '@tanstack/react-router';
import { FileTable } from '@/features/upload-history/ui/FileTable';
import { useCreateComparison } from '@/features/file-comparison/lib/useCreateComparison';
import { comparisonFormSchema, type ComparisonFormData } from '@/features/file-comparison/lib/comparisonSchema';
import { useBatchHistory } from '@/entities/batch/api/queries';
import { useBatchDetails } from '@/entities/batch/api/queries';
import { Button } from '@/shared/ui/ui/button';
import { toast } from 'sonner';

export function ComparisonPage() {
  const navigate = useNavigate();
  const search = useSearch({ from: '/account/comparisons/create' }) as { currentBatchId?: string; fileIds?: string };
  const [selectedFileIds, setSelectedFileIds] = useState<string[]>([]);
  const [currentBatchId, setCurrentBatchId] = useState<string>('');

  // Fetch batch history for dropdowns (completed batches only)
  const { data: batchHistory, isLoading: isLoadingBatches } = useBatchHistory();

  // Get all batches from infinite query pages
  const allBatches = batchHistory?.pages?.flatMap(page => page.items) || [];

  // Fetch current batch details if pre-selected
  const { data: currentBatch } = useBatchDetails(currentBatchId);

  // React Hook Form with Zod validation
  const {
    register,
    handleSubmit,
    formState: { errors },
    setValue,
    watch,
  } = useForm<ComparisonFormData>({
    resolver: zodResolver(comparisonFormSchema),
  });

  // Watch currentBatchId changes to load files
  const watchedCurrentBatchId = watch('currentBatchId');

  // Pre-fill form from URL parameters
  useEffect(() => {
    if (search?.currentBatchId) {
      setValue('currentBatchId', search.currentBatchId);
      setCurrentBatchId(search.currentBatchId);
    }
    if (search?.fileIds) {
      const fileIds = search.fileIds.split(',').filter(Boolean);
      setSelectedFileIds(fileIds);
      setValue('fileIds', fileIds);
    }
  }, [search, setValue]);

  // Update current batch when selection changes
  useEffect(() => {
    if (watchedCurrentBatchId) {
      setCurrentBatchId(watchedCurrentBatchId);
    }
  }, [watchedCurrentBatchId]);

  // TanStack Query mutation
  const createComparison = useCreateComparison({
    onSuccess: (data) => {
      toast.success('Comparison created successfully!', {
        description: `Comparison ID: ${data.id} - Status: ${data.status}`,
      });
      // Navigate to comparison details page
      navigate({ to: `/account/comparisons/${data.id}` });
    },
    onError: (error) => {
      toast.error('Failed to create comparison', {
        description: error.message,
      });
    },
  });

  // Filter completed batches for selection
  const completedBatches = allBatches.filter(
    (batch: any) => batch.status === 'COMPLETED'
  );

  // Handle file selection from FileTable
  const handleFileSelection = (fileIds: string[]) => {
    setSelectedFileIds(fileIds);
    setValue('fileIds', fileIds.length > 0 ? fileIds : null);
  };

  // Form submission
  const onSubmit = (data: ComparisonFormData) => {
    createComparison.mutate({
      currentBatchId: data.currentBatchId,
      targetBatchId: data.targetBatchId,
      fileIds: data.fileIds,
    });
  };

  return (
    <div className="container mx-auto px-4 py-8">
      <div className="mb-6">
        <h1 className="text-3xl font-bold">Create File Comparison</h1>
        <p className="mt-2 text-gray-600">
          Compare files between two upload sessions to track changes
        </p>
      </div>

      <form onSubmit={handleSubmit(onSubmit)} className="space-y-6">
        {/* Batch Selection Section */}
        <div className="rounded-lg border border-gray-200 bg-white p-6">
          <h2 className="mb-4 text-xl font-semibold">Select Batches</h2>
          
          <div className="grid gap-4 md:grid-cols-2">
            {/* Current Batch Selector */}
            <div>
              <label htmlFor="currentBatchId" className="block text-sm font-medium text-gray-700">
                Current Batch (Source)
              </label>
              <select
                id="currentBatchId"
                {...register('currentBatchId')}
                className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                disabled={isLoadingBatches}
              >
                <option value="">
                  {isLoadingBatches ? 'Loading batches...' : 'Select current batch...'}
                </option>
                {completedBatches.map((batch: any) => (
                  <option key={batch.id} value={batch.id}>
                    Batch {batch.id} - {batch.uploadedFilesCount} files ({new Date(batch.startedAt).toLocaleDateString()})
                  </option>
                ))}
              </select>
              {errors.currentBatchId && (
                <p className="mt-1 text-sm text-red-600">{errors.currentBatchId.message}</p>
              )}
            </div>

            {/* Target Batch Selector */}
            <div>
              <label htmlFor="targetBatchId" className="block text-sm font-medium text-gray-700">
                Target Batch (Baseline)
              </label>
              <select
                id="targetBatchId"
                {...register('targetBatchId')}
                className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                disabled={isLoadingBatches}
              >
                <option value="">
                  {isLoadingBatches ? 'Loading batches...' : 'Select target batch...'}
                </option>
                {completedBatches.map((batch: any) => (
                  <option key={batch.id} value={batch.id}>
                    Batch {batch.id} - {batch.uploadedFilesCount} files ({new Date(batch.startedAt).toLocaleDateString()})
                  </option>
                ))}
              </select>
              {errors.targetBatchId && (
                <p className="mt-1 text-sm text-red-600">{errors.targetBatchId.message}</p>
              )}
            </div>
          </div>
        </div>

        {/* File Selection Section */}
        <div className="rounded-lg border border-gray-200 bg-white p-6">
          <h2 className="mb-4 text-xl font-semibold">Select Files</h2>
          <p className="mb-4 text-sm text-gray-600">
            Choose specific files to compare, or select all files from the current batch.
          </p>

          {/* FileTable reused from Upload History */}
          {currentBatch?.files ? (
            <FileTable
              files={currentBatch.files}
              onSelectionChange={handleFileSelection}
            />
          ) : currentBatchId ? (
            <div className="rounded-md border border-gray-200 bg-gray-50 p-8 text-center">
              <p className="text-sm text-gray-600">Loading files from selected batch...</p>
            </div>
          ) : (
            <div className="rounded-md border border-gray-200 bg-gray-50 p-8 text-center">
              <p className="text-sm text-gray-600">
                Please select a current batch above to view and select files for comparison.
              </p>
            </div>
          )}

          {errors.fileIds && (
            <p className="mt-2 text-sm text-red-600">{errors.fileIds.message}</p>
          )}

          {selectedFileIds.length > 0 && (
            <div className="mt-4 rounded-md bg-blue-50 p-3">
              <p className="text-sm text-blue-800">
                <strong>{selectedFileIds.length}</strong> file(s) selected for comparison
              </p>
            </div>
          )}
        </div>

        {/* Action Buttons */}
        <div className="flex justify-end gap-3">
          <Button
            type="button"
            variant="outline"
            onClick={() => navigate({ to: '/account/upload-history' })}
            disabled={createComparison.isPending}
          >
            Cancel
          </Button>
          <Button
            type="submit"
            disabled={createComparison.isPending || selectedFileIds.length === 0}
          >
            {createComparison.isPending ? 'Creating...' : 'Create Comparison'}
          </Button>
        </div>

        {/* Error Display */}
        {createComparison.isError && (
          <div className="rounded-md bg-red-50 p-4">
            <p className="text-sm text-red-800">
              <strong>Error:</strong> {createComparison.error?.message || 'Failed to create comparison'}
            </p>
          </div>
        )}
      </form>
    </div>
  );
}

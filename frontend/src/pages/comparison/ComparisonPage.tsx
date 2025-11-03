/**
 * T044: Comparison creation page
 *
 * Integrates FileTable (reused from Upload History) with batch selection
 * and form validation for creating file comparisons.
 *
 * Feature: 009-markdown-user-story (User Story 1)
 */

import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useNavigate } from '@tanstack/react-router';
import { FileTable } from '@/features/upload-history/ui/FileTable';
import { useCreateComparison } from '@/features/file-comparison/lib/useCreateComparison';
import { comparisonFormSchema, type ComparisonFormData } from '@/features/file-comparison/lib/comparisonSchema';
import { Button } from '@/shared/ui/ui/button';
import { toast } from 'sonner';

export function ComparisonPage() {
  const navigate = useNavigate();
  const [selectedFileIds, setSelectedFileIds] = useState<string[]>([]);
  
  // React Hook Form with Zod validation
  const {
    register,
    handleSubmit,
    formState: { errors },
    setValue,
  } = useForm<ComparisonFormData>({
    resolver: zodResolver(comparisonFormSchema),
  });

  // TanStack Query mutation
  const createComparison = useCreateComparison({
    onSuccess: (data) => {
      toast.success('Comparison created successfully!', {
        description: `Comparison ID: ${data.id} - Status: ${data.status}`,
      });
      // Navigate back to upload history (comparison details will be implemented in US2)
      navigate({ to: '/account/upload-history' });
    },
    onError: (error) => {
      toast.error('Failed to create comparison', {
        description: error.message,
      });
    },
  });

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
              >
                <option value="">Select current batch...</option>
                {/* TODO: Populate from batch list query */}
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
              >
                <option value="">Select target batch...</option>
                {/* TODO: Populate from batch list query */}
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
          <FileTable
            files={[]} // TODO: Load files from selected current batch
            onSelectionChange={handleFileSelection}
          />

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

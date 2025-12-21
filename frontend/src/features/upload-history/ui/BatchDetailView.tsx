/**
 * T055: Batch detail view component
 *
 * Displays batch metadata and file list with selection capabilities.
 * Integrates FileTable component and shows batch status, timestamps, and file count.
 *
 * Feature: 008-upload-history-user (User Story 2, User Story 3, User Story 4)
 */

import { useState, useCallback } from 'react';
import { ArrowLeft, CheckCircle, XCircle, Loader2 } from 'lucide-react';
import type { BatchDetail } from '@/entities/batch/model/types';
import { formatBytes, formatDateTime } from '@/shared/lib/formatters';
import { FileTable } from './FileTable';
import { DownloadButton } from './DownloadButton';
import { ExcelButton } from './ExcelButton';
import { CompareButton } from './CompareButton';
import { ComparisonHistorySection } from './ComparisonHistorySection';
import { ErrorListView } from './ErrorListView';
import { useBatchErrors } from '@/entities/batch/api/queries';

interface BatchDetailViewProps {
  /** Batch details with file list */
  batch?: BatchDetail;
  /** Site name for display (resolved from siteId) */
  siteName?: string;
  /** Is data loading */
  isLoading: boolean;
  /** Error message if any */
  error?: string | null;
  /** Callback when user wants to go back to list */
  onBack?: () => void;
  /** Callback when file selection changes (optional - for external tracking) */
  onFileSelectionChange?: (selectedFileIds: string[]) => void;
}

/**
 * T055, T077: Batch detail view integrating FileTable, batch metadata, and download functionality
 */
export function BatchDetailView({
  batch,
  siteName,
  isLoading,
  error,
  onBack,
  onFileSelectionChange,
}: BatchDetailViewProps) {
  // T077: Track selected file IDs for download functionality
  const [selectedFileIds, setSelectedFileIds] = useState<string[]>([]);

  // T107: Track error pagination state
  const [errorPage, setErrorPage] = useState(0);
  const [errorPageSize, setErrorPageSize] = useState(20);

  // T107: Fetch batch errors (only if batch has errors)
  const {
    data: errors,
    isLoading: isLoadingErrors,
    error: errorsError,
  } = useBatchErrors(batch?.id ?? '', errorPage, errorPageSize, {
    enabled: batch?.hasErrors === true,
  });

  // Handle file selection changes from FileTable
  const handleSelectionChange = useCallback((fileIds: string[]) => {
    console.log('[BatchDetailView] handleSelectionChange called with:', fileIds);
    setSelectedFileIds(fileIds);
    onFileSelectionChange?.(fileIds);
  }, [onFileSelectionChange]);
  // Loading state
  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-12">
        <Loader2 className="h-8 w-8 animate-spin text-gray-400" />
        <span className="ml-2 text-gray-600">Loading batch details...</span>
      </div>
    );
  }

  // Error state
  if (error) {
    return (
      <div className="space-y-4">
        {onBack && (
          <button
            onClick={onBack}
            className="inline-flex items-center text-sm text-gray-600 hover:text-gray-900"
          >
            <ArrowLeft className="mr-1 h-4 w-4" />
            Back to list
          </button>
        )}
        <div className="rounded-lg border border-red-200 bg-red-50 p-4">
          <p className="text-sm text-red-800">{error}</p>
        </div>
      </div>
    );
  }

  // No data state
  if (!batch) {
    return (
      <div className="rounded-lg border border-gray-200 bg-gray-50 p-8 text-center">
        <p className="text-gray-600">Batch not found.</p>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Header with back button */}
      {onBack && (
        <button
          onClick={onBack}
          className="inline-flex items-center text-sm text-gray-600 hover:text-gray-900 transition-colors"
        >
          <ArrowLeft className="mr-1 h-4 w-4" />
          Back to list
        </button>
      )}

      {/* Batch metadata card */}
      <div className="rounded-lg border border-gray-200 bg-white p-6">
        <div className="flex items-start justify-between">
          {/* Status and basic info */}
          <div className="flex items-center space-x-4">
            {/* Icon based on batch status, not hasErrors */}
            {batch.status === 'COMPLETED' || batch.status === 'COMPLETED_WITH_WARNINGS' ? (
              <CheckCircle className="h-8 w-8 text-green-500 flex-shrink-0" />
            ) : batch.status === 'IN_PROGRESS' ? (
              <Loader2 className="h-8 w-8 text-blue-500 animate-spin flex-shrink-0" />
            ) : (
              <XCircle className="h-8 w-8 text-red-500 flex-shrink-0" />
            )}
            <div>
              <h2 className="text-xl font-semibold text-gray-900">
                Upload Session
              </h2>
              <p className="mt-1 text-sm text-gray-500">
                Batch ID: {batch.id}
              </p>
            </div>
          </div>

          {/* Status badge */}
          <span
            className={`inline-flex items-center rounded-full px-3 py-1 text-sm font-medium ${
              batch.status === 'COMPLETED'
                ? 'bg-green-100 text-green-800'
                : batch.status === 'COMPLETED_WITH_WARNINGS'
                ? 'bg-yellow-100 text-yellow-800'
                : batch.status === 'IN_PROGRESS'
                ? 'bg-blue-100 text-blue-800'
                : 'bg-red-100 text-red-800'
            }`}
          >
            {batch.status === 'COMPLETED_WITH_WARNINGS' ? 'Completed (Warnings)' : batch.status}
          </span>
        </div>

        {/* Metadata grid */}
        <div className="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-5">
          {/* Site name */}
          <div>
            <dt className="text-sm font-medium text-gray-500">Site</dt>
            <dd className="mt-1 text-sm text-gray-900">
              {siteName || 'Unknown site'}
            </dd>
          </div>

          <div>
            <dt className="text-sm font-medium text-gray-500">Started At</dt>
            <dd className="mt-1 text-sm text-gray-900">
              {formatDateTime(batch.startedAt)}
            </dd>
          </div>

          {batch.completedAt && (
            <div>
              <dt className="text-sm font-medium text-gray-500">Completed At</dt>
              <dd className="mt-1 text-sm text-gray-900">
                {formatDateTime(batch.completedAt)}
              </dd>
            </div>
          )}

          <div>
            <dt className="text-sm font-medium text-gray-500">Files</dt>
            <dd className="mt-1 text-sm text-gray-900">
              {batch.uploadedFilesCount} file{batch.uploadedFilesCount !== 1 ? 's' : ''}
            </dd>
          </div>

          <div>
            <dt className="text-sm font-medium text-gray-500">Total Size</dt>
            <dd className="mt-1 text-sm text-gray-900">
              {formatBytes(batch.totalSize)}
            </dd>
          </div>
        </div>
      </div>

      {/* Files section */}
      <div>
        <div className="mb-4 flex items-center justify-between">
          <h3 className="text-lg font-medium text-gray-900">
            Files ({batch.files.length})
          </h3>

          {/* T077, T099: Action buttons for selected files */}
          <div className="flex items-center gap-2">
            {/* T077: Download button for selected files */}
            <DownloadButton
              batchId={batch.id}
              selectedFileIds={selectedFileIds}
              batchStatus={batch.status}
              zipFilename={`batch-${batch.id}.zip`}
            />

            {/* T099: Excel export button for selected CSV files */}
            <ExcelButton
              batchId={batch.id}
              selectedFileIds={selectedFileIds}
              batchStatus={batch.status}
              excelFilename={`batch-${batch.id}.xlsx`}
            />

            {/* Spec 009: Compare files button */}
            <CompareButton
              batchId={batch.id}
              siteId={batch.siteId}
              selectedFileIds={selectedFileIds}
              batchStatus={batch.status}
            />
          </div>
        </div>

        <FileTable
          files={batch.files}
          onSelectionChange={handleSelectionChange}
        />
      </div>

      {/* T107: Conditionally show Errors or Comparison History (Added 2025-11-10) */}
      {batch.hasErrors ? (
        <div>
          <h3 className="mb-4 text-lg font-medium text-gray-900">
            Batch Errors
          </h3>
          <ErrorListView
            batchId={batch.id}
            errors={errors}
            isLoading={isLoadingErrors}
            error={errorsError?.message ?? null}
            currentPage={errorPage}
            pageSize={errorPageSize}
            onPageChange={setErrorPage}
            onPageSizeChange={setErrorPageSize}
          />
        </div>
      ) : (
        /* Comparison History Section (Added 2025-11-03) */
        <ComparisonHistorySection batchId={batch.id} />
      )}
    </div>
  );
}

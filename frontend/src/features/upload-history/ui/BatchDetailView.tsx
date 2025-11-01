/**
 * T055: Batch detail view component
 *
 * Displays batch metadata and file list with selection capabilities.
 * Integrates FileTable component and shows batch status, timestamps, and file count.
 *
 * Feature: 008-upload-history-user (User Story 2)
 */

import { ArrowLeft, CheckCircle, XCircle, Loader2 } from 'lucide-react';
import type { BatchDetail } from '@/entities/batch/model/types';
import { formatBytes, formatDateTime } from '@/shared/lib/formatters';
import { FileTable } from './FileTable';

interface BatchDetailViewProps {
  /** Batch details with file list */
  batch?: BatchDetail;
  /** Is data loading */
  isLoading: boolean;
  /** Error message if any */
  error?: string | null;
  /** Callback when user wants to go back to list */
  onBack?: () => void;
  /** Callback when file selection changes */
  onFileSelectionChange?: (selectedFileIds: string[]) => void;
}

/**
 * T055: Batch detail view integrating FileTable and batch metadata
 */
export function BatchDetailView({
  batch,
  isLoading,
  error,
  onBack,
  onFileSelectionChange,
}: BatchDetailViewProps) {
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
            {batch.hasErrors ? (
              <XCircle className="h-8 w-8 text-red-500 flex-shrink-0" />
            ) : (
              <CheckCircle className="h-8 w-8 text-green-500 flex-shrink-0" />
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
                : batch.status === 'IN_PROGRESS'
                ? 'bg-blue-100 text-blue-800'
                : 'bg-red-100 text-red-800'
            }`}
          >
            {batch.status}
          </span>
        </div>

        {/* Metadata grid */}
        <div className="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
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

        {/* Error indicator */}
        {batch.hasErrors && (
          <div className="mt-4 rounded-md bg-red-50 p-3">
            <p className="text-sm text-red-800">
              This batch has errors. Check the error logs for details.
            </p>
          </div>
        )}
      </div>

      {/* Files section */}
      <div>
        <h3 className="mb-4 text-lg font-medium text-gray-900">
          Files ({batch.files.length})
        </h3>
        <FileTable
          files={batch.files}
          onSelectionChange={onFileSelectionChange}
        />
      </div>
    </div>
  );
}

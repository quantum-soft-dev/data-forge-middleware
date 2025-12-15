/**
 * T037: Batch list view component with infinite scroll
 *
 * Displays paginated list of upload sessions with status indicators
 * and infinite scroll for loading more batches.
 *
 * Feature: 008-upload-history-user (User Story 1)
 * Phase 7: Added "View errors" button for batches with errors (T108)
 */

import { useState, useMemo } from 'react';
import { CheckCircle, XCircle, Loader2, AlertCircle, Filter } from 'lucide-react';
import type { BatchSummary } from '@/entities/batch/model/types';
import { formatBytes, formatDateTime } from '@/shared/lib/formatters';

interface BatchListViewProps {
  /** Batches from all loaded pages (flattened) */
  batches: BatchSummary[];
  /** Is initial data loading */
  isLoading: boolean;
  /** Has more pages to load */
  hasNextPage: boolean;
  /** Is fetching next page */
  isFetchingNextPage: boolean;
  /** Function to load next page */
  onLoadMore: () => void;
  /** Error message if any */
  error?: string | null;
  /** Callback when batch is clicked (T057) */
  onBatchClick?: (batchId: string) => void;
  /** Callback when "View errors" button is clicked (T108 - Phase 7) */
  onViewErrors?: (batchId: string) => void;
}

/**
 * T037: Batch list view with infinite scroll and status indicators
 */
export function BatchListView({
  batches,
  isLoading,
  hasNextPage,
  isFetchingNextPage,
  onLoadMore,
  error,
  onBatchClick,
  onViewErrors,
}: BatchListViewProps) {
  const [statusFilter, setStatusFilter] = useState<string>('all');
  const [dateFilter, setDateFilter] = useState<string>('all');

  // Filter batches
  const filteredBatches = useMemo(() => {
    let filtered = batches;

    // Filter by status
    if (statusFilter !== 'all') {
      filtered = filtered.filter(batch => batch.status === statusFilter);
    }

    // Filter by date (last 7 days, last 30 days, etc.)
    if (dateFilter !== 'all') {
      const now = new Date();
      const filterDate = new Date();

      switch (dateFilter) {
        case '7days':
          filterDate.setDate(now.getDate() - 7);
          break;
        case '30days':
          filterDate.setDate(now.getDate() - 30);
          break;
        case '90days':
          filterDate.setDate(now.getDate() - 90);
          break;
      }

      filtered = filtered.filter(batch =>
        new Date(batch.startedAt) >= filterDate
      );
    }

    return filtered;
  }, [batches, statusFilter, dateFilter]);
  // Loading state
  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-12">
        <Loader2 className="h-8 w-8 animate-spin text-gray-400" />
        <span className="ml-2 text-gray-600">Loading upload history...</span>
      </div>
    );
  }

  // Error state
  if (error) {
    return (
      <div className="rounded-lg border border-red-200 bg-red-50 p-4">
        <p className="text-sm text-red-800">{error}</p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Filters */}
      <div className="flex gap-3 items-center bg-gray-50 p-3 rounded-lg border border-gray-200">
        <Filter className="h-4 w-4 text-gray-500" />
        <select
          value={statusFilter}
          onChange={(e) => setStatusFilter(e.target.value)}
          className="px-3 py-1.5 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
        >
          <option value="all">All Status</option>
          <option value="COMPLETED">Completed</option>
          <option value="COMPLETED_WITH_WARNINGS">Completed with Warnings</option>
          <option value="IN_PROGRESS">In Progress</option>
          <option value="FAILED">Failed</option>
        </select>
        <select
          value={dateFilter}
          onChange={(e) => setDateFilter(e.target.value)}
          className="px-3 py-1.5 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
        >
          <option value="all">All Time</option>
          <option value="7days">Last 7 days</option>
          <option value="30days">Last 30 days</option>
          <option value="90days">Last 90 days</option>
        </select>
        {(statusFilter !== 'all' || dateFilter !== 'all') && (
          <button
            onClick={() => {
              setStatusFilter('all');
              setDateFilter('all');
            }}
            className="text-sm text-blue-600 hover:text-blue-800"
          >
            Clear filters
          </button>
        )}
      </div>

      {/* Empty/filtered state */}
      {filteredBatches.length === 0 && (
        <div className="rounded-lg border border-gray-200 bg-gray-50 p-8 text-center">
          <p className="text-gray-600">
            {batches.length === 0
              ? 'No upload history found.'
              : 'No batches match your filters.'}
          </p>
          <p className="mt-2 text-sm text-gray-500">
            {batches.length === 0
              ? 'Upload sessions will appear here after you start uploading files.'
              : 'Try adjusting your filters to see more results.'}
          </p>
        </div>
      )}

      {/* Batch list with scrolling */}
      {filteredBatches.length > 0 && (
        <div className="max-h-[600px] overflow-y-auto divide-y divide-gray-200 rounded-lg border border-gray-200">
          {filteredBatches.map((batch) => (
            <div
              key={batch.id}
              className={`flex items-center justify-between p-3 hover:bg-gray-50 transition-colors ${
                onBatchClick ? 'cursor-pointer' : ''
              }`}
              onClick={() => onBatchClick?.(batch.id)}
              role={onBatchClick ? 'button' : undefined}
              tabIndex={onBatchClick ? 0 : undefined}
              onKeyDown={(e) => {
                if (onBatchClick && (e.key === 'Enter' || e.key === ' ')) {
                  e.preventDefault();
                  onBatchClick(batch.id);
                }
              }}
            >
              {/* Status indicator */}
              <div className="flex items-center space-x-3 flex-1">
                {batch.hasErrors ? (
                  <XCircle className="h-5 w-5 text-red-500 flex-shrink-0" />
                ) : (
                  <CheckCircle className="h-5 w-5 text-green-500 flex-shrink-0" />
                )}

                {/* Batch info */}
                <div>
                  <div className="flex items-center space-x-2">
                    <span className="text-sm font-medium text-gray-900">
                      {formatDateTime(batch.startedAt)}
                    </span>
                    <span
                      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${
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
                  <div className="mt-0.5 text-xs text-gray-500">
                    {batch.uploadedFilesCount} files • {formatBytes(batch.totalSize)}
                  </div>
                </div>
              </div>

              {/* Right side: Completed timestamp and View errors button */}
              <div className="flex items-center space-x-3">
                {/* Completed timestamp */}
                {batch.completedAt && (
                  <div className="text-xs text-gray-500">
                    Completed: {formatDateTime(batch.completedAt)}
                  </div>
                )}

                {/* T108: View errors button (Phase 7) */}
                {batch.hasErrors && onViewErrors && (
                  <button
                    onClick={(e) => {
                      e.stopPropagation(); // Prevent triggering batch click
                      onViewErrors(batch.id);
                    }}
                    className="inline-flex items-center space-x-1 rounded-md border border-red-300 bg-red-50 px-2 py-1 text-xs font-medium text-red-700 hover:bg-red-100 transition-colors"
                  >
                    <AlertCircle className="h-3 w-3" />
                    <span>Errors</span>
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Load more button */}
      {hasNextPage && (
        <div className="flex justify-center py-4">
          <button
            onClick={onLoadMore}
            disabled={isFetchingNextPage}
            className="inline-flex items-center rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {isFetchingNextPage ? (
              <>
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                Loading...
              </>
            ) : (
              'Load More'
            )}
          </button>
        </div>
      )}
    </div>
  );
}

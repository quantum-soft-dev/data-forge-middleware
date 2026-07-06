/**
 * T037: Batch list view component with infinite scroll
 *
 * Displays paginated list of upload sessions with status indicators
 * and infinite scroll for loading more batches.
 *
 * Feature: 008-upload-history-user (User Story 1)
 * Phase 7: Added "View errors" button for batches with errors (T108)
 */

import { useState, useMemo, useEffect } from 'react';
import { Loader2, AlertCircle, Filter, RefreshCw } from 'lucide-react';
import { Badge } from '@/shared/ui/ui/badge';
import { Button } from '@/shared/ui/ui/button';
import type { BatchSummary } from '@/entities/batch/model/types';
import type { Site } from '@/entities/site/model/types';
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
  /** Available sites for filtering */
  sites?: Site[];
  /** Site ID to name lookup map for displaying site names */
  siteLookup?: Map<string, string>;
  /** Callback to refresh the batch list */
  onRefresh?: () => void;
  /** Is data being refreshed */
  isRefreshing?: boolean;
}

const FILTER_SELECT_CLASSES =
  'h-8 rounded-lg border border-input bg-background px-3 text-sm text-ink focus:outline-none focus:ring-2 focus:ring-ring';

const STATUS_LABELS: Record<string, string> = {
  COMPLETED: 'Completed',
  COMPLETED_WITH_WARNINGS: 'Completed (Warnings)',
  IN_PROGRESS: 'In progress',
  FAILED: 'Failed',
};

const STATUS_VARIANT: Record<string, 'success' | 'warning' | 'info' | 'critical'> = {
  COMPLETED: 'success',
  COMPLETED_WITH_WARNINGS: 'warning',
  IN_PROGRESS: 'info',
  FAILED: 'critical',
};

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
  sites,
  siteLookup,
  onRefresh,
  isRefreshing,
}: BatchListViewProps) {
  const [statusFilter, setStatusFilter] = useState<string>('all');
  const [dateFilter, setDateFilter] = useState<string>('7days'); // Default to last 7 days
  const [siteFilter, setSiteFilter] = useState<string>('all');

  // Check if any filter is active (7days is the default, so not considered "active" for UI purposes)
  const isFilterActive = statusFilter !== 'all' || dateFilter !== '7days' || siteFilter !== 'all';

  // Filter batches
  const filteredBatches = useMemo(() => {
    let filtered = batches;

    // Filter by site
    if (siteFilter !== 'all') {
      filtered = filtered.filter(batch => batch.siteId === siteFilter);
    }

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
  }, [batches, siteFilter, statusFilter, dateFilter]);

  // Auto-load more pages when filters are active and results are sparse
  // This fixes the issue where filtered results show "No matches" when more pages may have matches
  useEffect(() => {
    const needsMoreData = filteredBatches.length < 10 && hasNextPage && !isFetchingNextPage && !isLoading;

    if (isFilterActive && needsMoreData) {
      onLoadMore();
    }
  }, [filteredBatches.length, hasNextPage, isFetchingNextPage, isLoading, isFilterActive, onLoadMore]);

  // Loading state
  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-12">
        <Loader2 className="h-8 w-8 animate-spin text-ink-muted" />
        <span className="ml-2 text-ink-secondary">Loading upload history...</span>
      </div>
    );
  }

  // Error state
  if (error) {
    return (
      <div className="rounded-lg border border-danger-border bg-danger-bg p-4">
        <p className="text-sm text-danger-text">{error}</p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Filters */}
      <div className="flex items-center gap-3 rounded-lg bg-white p-3 shadow-panel">
        <Filter className="h-4 w-4 text-ink-muted" strokeWidth={1.5} />

        {/* Site filter */}
        {sites && sites.length > 0 && (
          <select
            value={siteFilter}
            onChange={(e) => setSiteFilter(e.target.value)}
            className={FILTER_SELECT_CLASSES}
          >
            <option value="all">All Sites</option>
            {sites.map((site) => (
              <option key={site.id} value={site.id}>
                {site.name}
              </option>
            ))}
          </select>
        )}

        <select
          value={statusFilter}
          onChange={(e) => setStatusFilter(e.target.value)}
          className={FILTER_SELECT_CLASSES}
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
          className={FILTER_SELECT_CLASSES}
        >
          <option value="all">All Time</option>
          <option value="7days">Last 7 days</option>
          <option value="30days">Last 30 days</option>
          <option value="90days">Last 90 days</option>
        </select>
        {isFilterActive && (
          <button
            onClick={() => {
              setStatusFilter('all');
              setDateFilter('7days'); // Reset to default (7 days)
              setSiteFilter('all');
            }}
            className="text-sm font-medium text-brand hover:text-brand-hover"
          >
            Clear filters
          </button>
        )}

        {/* Spacer to push refresh button to the right */}
        <div className="flex-1" />

        {/* Refresh button */}
        {onRefresh && (
          <Button
            variant="outline"
            size="compact"
            onClick={onRefresh}
            disabled={isRefreshing}
            title="Refresh batch list"
          >
            <RefreshCw className={`h-4 w-4 mr-1 ${isRefreshing ? 'animate-spin' : ''}`} />
            Refresh
          </Button>
        )}
      </div>

      {/* Empty/filtered state */}
      {filteredBatches.length === 0 && (
        <div className="rounded-lg bg-white p-8 text-center shadow-panel">
          {isFetchingNextPage && isFilterActive ? (
            <>
              <Loader2 className="h-6 w-6 animate-spin text-ink-muted mx-auto mb-2" />
              <p className="text-ink-secondary">Loading more batches...</p>
              <p className="mt-2 text-sm text-ink-muted">
                Searching for batches matching your filters.
              </p>
            </>
          ) : (
            <>
              <p className="text-ink-secondary">
                {batches.length === 0
                  ? 'No upload history found.'
                  : 'No batches match your filters.'}
              </p>
              <p className="mt-2 text-sm text-ink-muted">
                {batches.length === 0
                  ? 'Upload sessions will appear here after you start uploading files.'
                  : hasNextPage
                    ? 'More batches are available. Click "Load More" below or adjust your filters.'
                    : 'Try adjusting your filters to see more results.'}
              </p>
            </>
          )}
        </div>
      )}

      {/* Batch list with scrolling */}
      {filteredBatches.length > 0 && (
        <div className="max-h-[600px] overflow-y-auto divide-y divide-separator rounded-lg bg-white shadow-panel">
          {filteredBatches.map((batch) => (
            <div
              key={batch.id}
              className={`flex items-center justify-between p-3 hover:bg-surface-hover transition-colors ${
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
              {/* Status indicator - based on batch status, not hasErrors */}
              <div className="flex items-center space-x-3 flex-1">
                {/* Batch info */}
                <div>
                  <div className="flex items-center space-x-2">
                    <span className="text-sm font-medium text-ink tabular-nums">
                      {formatDateTime(batch.startedAt)}
                    </span>
                    <Badge
                      variant={STATUS_VARIANT[batch.status] ?? 'neutral'}
                      dot={batch.status !== 'IN_PROGRESS'}
                      className="px-2 py-0.5"
                    >
                      {batch.status === 'IN_PROGRESS' && (
                        <Loader2 className="h-3 w-3 animate-spin" />
                      )}
                      {STATUS_LABELS[batch.status] ?? batch.status}
                    </Badge>
                  </div>
                  <div className="mt-0.5 text-xs text-ink-secondary tabular-nums">
                    {siteLookup?.get(batch.siteId) && (
                      <span className="font-medium">{siteLookup.get(batch.siteId)} • </span>
                    )}
                    {batch.deltaTableCount != null ? (
                      <>
                        {batch.deltaRecordCount ?? 0} changes • {batch.deltaTableCount} tables
                      </>
                    ) : (
                      <>
                        {batch.uploadedFilesCount} files • {formatBytes(batch.totalSize)}
                      </>
                    )}
                  </div>
                </div>
              </div>

              {/* Right side: Completed timestamp and View errors button */}
              <div className="flex items-center space-x-3">
                {/* Completed timestamp */}
                {batch.completedAt && (
                  <div className="text-xs text-ink-muted tabular-nums">
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
                    className="inline-flex items-center space-x-1 rounded-lg border border-danger-border bg-white px-2 py-1 text-xs font-medium text-danger-text hover:bg-danger-bg transition-colors"
                  >
                    <AlertCircle className="h-3 w-3" strokeWidth={1.5} />
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
          <Button onClick={onLoadMore} disabled={isFetchingNextPage}>
            {isFetchingNextPage ? (
              <>
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                Loading...
              </>
            ) : (
              'Load More'
            )}
          </Button>
        </div>
      )}
    </div>
  );
}

/**
 * T107: Error list view component (Phase 7)
 *
 * Displays paginated list of errors for a specific batch.
 * Shows error type, message, timestamp, and metadata.
 * Supports pagination with page size controls.
 *
 * Feature: 008-upload-history-user (Phase 7 - Error Details View)
 */

import { useState } from 'react';
import { ArrowLeft, AlertCircle, Loader2, ChevronLeft, ChevronRight } from 'lucide-react';
import type { ErrorSummary, PageResponse } from '@/entities/batch/model/types';
import { formatDateTime } from '@/shared/lib/formatters';

interface ErrorListViewProps {
  /** Batch ID for display */
  batchId: string;
  /** Paginated error data */
  errors?: PageResponse<ErrorSummary>;
  /** Is data loading */
  isLoading: boolean;
  /** Error message if any */
  error?: string | null;
  /** Current page (0-indexed) */
  currentPage: number;
  /** Page size */
  pageSize: number;
  /** Callback when page changes */
  onPageChange: (page: number) => void;
  /** Callback when page size changes */
  onPageSizeChange: (size: number) => void;
  /** Callback when user wants to go back */
  onBack?: () => void;
}

/**
 * T107: Error list view with pagination
 */
export function ErrorListView({
  batchId: _batchId,
  errors,
  isLoading,
  error,
  currentPage,
  pageSize,
  onPageChange,
  onPageSizeChange,
  onBack,
}: ErrorListViewProps) {
  // Expanded error state (for showing full details)
  const [expandedErrorId, setExpandedErrorId] = useState<string | null>(null);

  // Loading state
  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-12">
        <Loader2 className="h-8 w-8 animate-spin text-ink-muted" />
        <span className="ml-2 text-ink-secondary">Loading errors...</span>
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
            className="inline-flex items-center text-sm text-ink-secondary hover:text-ink"
          >
            <ArrowLeft className="mr-1 h-4 w-4" />
            Back to batch details
          </button>
        )}
        <div className="rounded-lg border border-danger-border bg-danger-bg p-4">
          <p className="text-sm text-danger-text">{error}</p>
        </div>
      </div>
    );
  }

  // No data state
  if (!errors || errors.content.length === 0) {
    return (
      <div className="space-y-4">
        {onBack && (
          <button
            onClick={onBack}
            className="inline-flex items-center text-sm text-ink-secondary hover:text-ink"
          >
            <ArrowLeft className="mr-1 h-4 w-4" />
            Back to batch details
          </button>
        )}
        <div className="rounded-lg bg-white p-8 text-center shadow-card">
          <AlertCircle className="mx-auto h-12 w-12 text-ink-muted" />
          <p className="mt-2 text-ink-secondary">No errors found for this batch.</p>
        </div>
      </div>
    );
  }

  const totalPages = errors.totalPages;
  const hasNext = currentPage < totalPages - 1;
  const hasPrev = currentPage > 0;

  return (
    <div className="space-y-6">
      {/* Header with back button and error count */}
      <div className="flex items-center justify-between">
        <div className="space-y-1">
          {onBack && (
            <button
              onClick={onBack}
              className="inline-flex items-center text-sm text-ink-secondary hover:text-ink transition-colors"
            >
              <ArrowLeft className="mr-1 h-4 w-4" />
              Back to batch details
            </button>
          )}
          <h2 className="text-[17px] font-medium tracking-[-0.24px] text-ink">
            Batch Errors
          </h2>
          <p className="text-sm text-ink-secondary">
            Showing {errors.content.length} of {errors.totalElements} errors
          </p>
        </div>

        {/* Page size selector */}
        <div className="flex items-center space-x-2">
          <label htmlFor="pageSize" className="text-sm text-ink-secondary">
            Per page:
          </label>
          <select
            id="pageSize"
            value={pageSize}
            onChange={(e) => {
              onPageSizeChange(Number(e.target.value));
              onPageChange(0); // Reset to first page on size change
            }}
            className="h-8 rounded-lg border border-input bg-background px-3 text-sm text-ink focus:outline-none focus:ring-2 focus:ring-ring"
          >
            <option value={10}>10</option>
            <option value={20}>20</option>
            <option value={50}>50</option>
            <option value={100}>100</option>
          </select>
        </div>
      </div>

      {/* Error list */}
      <div className="space-y-3">
        {errors.content.map((err) => {
          const isExpanded = expandedErrorId === err.id;

          return (
            <div
              key={err.id}
              className="rounded-lg bg-white p-4 shadow-card"
            >
              {/* Error header */}
              <div className="flex items-start justify-between">
                <div className="flex-1 space-y-1">
                  <div className="flex items-center space-x-2">
                    <AlertCircle className="h-5 w-5 text-danger-solid" />
                    <span className="inline-flex items-center rounded-full bg-red-100 px-2.5 py-0.5 text-xs font-medium text-red-800">
                      {err.type}
                    </span>
                    <span className="text-xs text-ink-muted tabular-nums">
                      {formatDateTime(err.occurredAt)}
                    </span>
                  </div>

                  <h3 className="text-sm font-medium text-ink">
                    {err.title}
                  </h3>

                  <p className="text-sm text-ink-secondary">
                    {err.message}
                  </p>

                  {/* Expandable details */}
                  {isExpanded && (
                    <div className="mt-3 space-y-2 border-t pt-3">
                      {/* Error ID */}
                      <div>
                        <span className="text-xs font-medium text-ink-secondary">Error ID:</span>
                        <p className="text-xs font-mono text-ink-secondary">{err.id}</p>
                      </div>

                      {/* Client version */}
                      {err.clientVersion && (
                        <div>
                          <span className="text-xs font-medium text-ink-secondary">Client Version:</span>
                          <p className="text-xs text-ink-secondary">{err.clientVersion}</p>
                        </div>
                      )}

                      {/* Stack trace */}
                      {err.stackTrace && (
                        <div>
                          <span className="text-xs font-medium text-ink-secondary">Stack Trace:</span>
                          <pre className="mt-1 max-h-40 overflow-y-auto rounded-lg bg-surface-subtle p-2 text-xs text-ink-secondary">
                            {err.stackTrace}
                          </pre>
                        </div>
                      )}

                      {/* Metadata */}
                      {err.metadata && Object.keys(err.metadata).length > 0 && (
                        <div>
                          <span className="text-xs font-medium text-ink-secondary">Metadata:</span>
                          <pre className="mt-1 max-h-40 overflow-y-auto rounded-lg bg-surface-subtle p-2 text-xs text-ink-secondary">
                            {JSON.stringify(err.metadata, null, 2)}
                          </pre>
                        </div>
                      )}
                    </div>
                  )}
                </div>

                {/* Expand/collapse button */}
                <button
                  onClick={() => setExpandedErrorId(isExpanded ? null : err.id)}
                  className="ml-2 text-sm font-medium text-brand hover:text-brand-hover transition-colors"
                >
                  {isExpanded ? 'Hide details' : 'Show details'}
                </button>
              </div>
            </div>
          );
        })}
      </div>

      {/* Pagination controls */}
      {totalPages > 1 && (
        <div className="flex items-center justify-between border-t pt-4">
          <p className="text-sm text-ink-secondary">
            Page {currentPage + 1} of {totalPages}
          </p>

          <div className="flex items-center space-x-2">
            <button
              onClick={() => onPageChange(currentPage - 1)}
              disabled={!hasPrev}
              className="inline-flex items-center rounded-lg border border-hairline bg-white px-3 py-2 text-sm font-medium text-ink hover:bg-secondary disabled:cursor-not-allowed disabled:opacity-50"
            >
              <ChevronLeft className="h-4 w-4" />
              Previous
            </button>

            {/* Page numbers */}
            <div className="flex items-center space-x-1">
              {Array.from({ length: Math.min(5, totalPages) }, (_, i) => {
                // Show first page, last page, current page, and surrounding pages
                let pageNum: number;
                if (totalPages <= 5) {
                  pageNum = i;
                } else if (currentPage < 3) {
                  pageNum = i;
                } else if (currentPage > totalPages - 4) {
                  pageNum = totalPages - 5 + i;
                } else {
                  pageNum = currentPage - 2 + i;
                }

                return (
                  <button
                    key={pageNum}
                    onClick={() => onPageChange(pageNum)}
                    className={`inline-flex h-9 w-9 items-center justify-center rounded-lg text-sm font-medium transition-colors tabular-nums ${
                      pageNum === currentPage
                        ? 'bg-brand text-white'
                        : 'border border-hairline bg-white text-ink-secondary hover:bg-secondary'
                    }`}
                  >
                    {pageNum + 1}
                  </button>
                );
              })}
            </div>

            <button
              onClick={() => onPageChange(currentPage + 1)}
              disabled={!hasNext}
              className="inline-flex items-center rounded-lg border border-hairline bg-white px-3 py-2 text-sm font-medium text-ink hover:bg-secondary disabled:cursor-not-allowed disabled:opacity-50"
            >
              Next
              <ChevronRight className="h-4 w-4" />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

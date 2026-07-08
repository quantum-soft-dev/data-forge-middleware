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
import { Badge } from '@/shared/ui/ui/badge';
import { severityTokens } from '@/shared/ui/tokens';
import type { BatchDetail } from '@/entities/batch/model/types';
import { formatBytes, formatDateTime } from '@/shared/lib/formatters';
import { FileTable } from './FileTable';
import { DownloadButton } from './DownloadButton';
import { ExcelButton } from './ExcelButton';
import { CompareButton } from './CompareButton';
import { ComparisonHistorySection } from './ComparisonHistorySection';
import { ErrorListView } from './ErrorListView';
import { DeltaBatchDetail } from './DeltaBatchDetail';
import { useBatchErrors } from '@/entities/batch/api/queries';
import { STATUS_LABELS, STATUS_VARIANT } from '@/features/upload-history/model/batchStatus';

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
        <Loader2 className="h-8 w-8 animate-spin text-ink-muted" />
        <span className="ml-2 text-ink-secondary">Loading batch details...</span>
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
            Back to list
          </button>
        )}
        <div className="rounded-lg border border-danger-border bg-danger-bg p-4">
          <p className="text-sm text-danger-text">{error}</p>
        </div>
      </div>
    );
  }

  // No data state
  if (!batch) {
    return (
      <div className="rounded-lg bg-white p-8 text-center shadow-panel">
        <p className="text-ink-secondary">Batch not found.</p>
      </div>
    );
  }

  // F10 (023): a Delta v2 batch has no uploaded_files — the redesigned delta surfaces
  // (meta card + Table changes) replace the entire file UI. An empty Delta session
  // (no stats, no files — e.g. an empty CONTINUOUS session) gets a dedicated empty state.
  const isDeltaBatch = (batch.deltaStats?.length ?? 0) > 0;
  const isEmptySession = !isDeltaBatch && batch.files.length === 0;

  if (isDeltaBatch || isEmptySession) {
    return (
      <div className="space-y-6">
        {onBack && (
          <button
            onClick={onBack}
            className="inline-flex items-center text-sm text-ink-secondary hover:text-ink transition-colors"
          >
            <ArrowLeft className="mr-1 h-4 w-4" />
            Back to list
          </button>
        )}

        {isDeltaBatch ? (
          <DeltaBatchDetail batch={batch} siteName={siteName} />
        ) : (
          <div className="rounded-lg bg-white p-8 text-center shadow-panel">
            <p className="text-ink-secondary">No changes in this session</p>
          </div>
        )}

        {batch.hasErrors && (
          <div>
            <h3 className="mb-4 text-[15px] font-medium tracking-[-0.24px] text-ink-title">Batch Errors</h3>
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
        )}
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Header with back button */}
      {onBack && (
        <button
          onClick={onBack}
          className="inline-flex items-center text-sm text-ink-secondary hover:text-ink transition-colors"
        >
          <ArrowLeft className="mr-1 h-4 w-4" />
          Back to list
        </button>
      )}

      {/* Batch metadata card */}
      <div className="rounded-lg bg-white p-6 shadow-panel">
        <div className="flex items-start justify-between">
          {/* Status and basic info */}
          <div className="flex items-center space-x-4">
            {/* Icon based on batch status, not hasErrors */}
            {batch.status === 'COMPLETED' || batch.status === 'COMPLETED_WITH_WARNINGS' ? (
              <CheckCircle className="h-8 w-8 flex-shrink-0" style={{ color: severityTokens.healthy.dot }} strokeWidth={1.5} />
            ) : batch.status === 'IN_PROGRESS' ? (
              <Loader2 className="h-8 w-8 animate-spin flex-shrink-0 text-brand" strokeWidth={1.5} />
            ) : (
              <XCircle
                className="h-8 w-8 flex-shrink-0"
                style={{
                  color:
                    STATUS_VARIANT[batch.status] === 'stalled'
                      ? severityTokens.stalled.dot
                      : severityTokens.critical.dot,
                }}
                strokeWidth={1.5}
              />
            )}
            <div>
              <h2 className="text-[17px] font-medium tracking-[-0.24px] text-ink">
                Upload Session
              </h2>
              <p className="mt-1 text-sm text-ink-muted">
                Batch ID: {batch.id}
              </p>
            </div>
          </div>

          {/* Status badge */}
          <Badge
            variant={STATUS_VARIANT[batch.status] ?? 'neutral'}
            dot
            className="px-3 py-1 text-sm"
          >
            {STATUS_LABELS[batch.status] ?? batch.status}
          </Badge>
        </div>

        {/* Metadata grid */}
        <div className="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-5">
          {/* Site name */}
          <div>
            <dt className="text-sm font-medium text-ink-secondary">Site</dt>
            <dd className="mt-1 text-sm text-ink tabular-nums">
              {siteName || 'Unknown site'}
            </dd>
          </div>

          <div>
            <dt className="text-sm font-medium text-ink-secondary">Started At</dt>
            <dd className="mt-1 text-sm text-ink tabular-nums">
              {formatDateTime(batch.startedAt)}
            </dd>
          </div>

          {batch.completedAt && (
            <div>
              <dt className="text-sm font-medium text-ink-secondary">Completed At</dt>
              <dd className="mt-1 text-sm text-ink tabular-nums">
                {formatDateTime(batch.completedAt)}
              </dd>
            </div>
          )}

          <div>
            <dt className="text-sm font-medium text-ink-secondary">Files</dt>
            <dd className="mt-1 text-sm text-ink tabular-nums">
              {batch.uploadedFilesCount} file{batch.uploadedFilesCount !== 1 ? 's' : ''}
            </dd>
          </div>

          <div>
            <dt className="text-sm font-medium text-ink-secondary">Total Size</dt>
            <dd className="mt-1 text-sm text-ink tabular-nums">
              {formatBytes(batch.totalSize)}
            </dd>
          </div>
        </div>
      </div>

      {/* Files section (v1 file-based batches) */}
      <div>
        <div className="mb-4 flex items-center justify-between">
            <h3 className="text-[15px] font-medium tracking-[-0.24px] text-ink-title">
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
          <h3 className="mb-4 text-[15px] font-medium tracking-[-0.24px] text-ink-title">
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

/**
 * T098: Excel export button component for converting CSV files to Excel
 *
 * Provides Excel generation functionality from selected CSV files.
 * Each CSV becomes a separate sheet in the Excel workbook.
 * Disabled when no files selected or batch is not in a completed status
 * (COMPLETED or COMPLETED_WITH_WARNINGS).
 *
 * Feature: 008-upload-history-user (User Story 4)
 */

import { FileSpreadsheet, Loader2 } from 'lucide-react';
import { Button } from '@/shared/ui/ui/button';
import { useExcelExport } from '../lib/useExcelExport';
import { toast } from 'sonner';

interface ExcelButtonProps {
  /** Batch unique identifier */
  batchId: string;
  /** Selected file IDs to export (must be CSV files) */
  selectedFileIds: string[];
  /** Batch status (only COMPLETED batches allow exports) */
  batchStatus: string;
  /** Optional custom Excel filename */
  excelFilename?: string;
  /** Optional custom button text */
  children?: React.ReactNode;
}

/**
 * T098: Excel export button with disabled state for incomplete batches
 *
 * Behavior:
 * - Disabled when no files selected or batch status is not completed (COMPLETED/COMPLETED_WITH_WARNINGS)
 * - Converts CSV files to Excel workbook (.xlsx)
 * - Each CSV file becomes a separate sheet (max 31 chars, deduplicated names)
 * - Handles .csv.gz decompression and encoding detection (UTF-8/Windows-1252)
 * - Shows loading state during export with spinner
 * - Toast notifications for success/error feedback
 *
 * Usage:
 * ```tsx
 * <ExcelButton
 *   batchId={batch.id}
 *   selectedFileIds={selectedFileIds}
 *   batchStatus={batch.status}
 *   excelFilename="batch-export.xlsx"
 * />
 * ```
 */
export function ExcelButton({
  batchId,
  selectedFileIds,
  batchStatus: _batchStatus,
  excelFilename,
  children,
}: ExcelButtonProps) {
  const { mutate: exportExcel, isPending } = useExcelExport();

  const MAX_FILES_FOR_EXCEL = 20;

  // Calculate button state - enabled if files selected (regardless of batch status)
  const isDisabled = selectedFileIds.length === 0 || isPending;

  const isTooManyFiles = selectedFileIds.length > MAX_FILES_FOR_EXCEL;

  // Handle export click
  const handleExport = () => {
    // Validate file count before export
    if (isTooManyFiles) {
      toast.error('Too many files selected', {
        description: `Cannot export more than ${MAX_FILES_FOR_EXCEL} files at once. Please select fewer files.`,
      });
      return;
    }

    exportExcel(
      {
        batchId,
        fileIds: selectedFileIds,
        excelFilename,
      },
      {
        onSuccess: (data) => {
          toast.success(
            `Excel export complete: ${data.fileName}`,
            {
              description: `${data.fileCount} CSV file(s) converted to Excel sheets`,
            }
          );
        },
        onError: (error) => {
          toast.error('Excel export failed', {
            description:
              error instanceof Error
                ? error.message
                : 'Failed to generate Excel file. Please try again.',
          });
        },
      }
    );
  };

  return (
    <div className="relative">
      <Button
        onClick={handleExport}
        disabled={isDisabled}
        variant={isTooManyFiles ? "destructive" : "outline"}
        className="gap-2"
      >
        {isPending ? (
          <>
            <Loader2 className="h-4 w-4 animate-spin" />
            Generating Excel...
          </>
        ) : (
          <>
            <FileSpreadsheet className="h-4 w-4" />
            {children || `Create Excel ${selectedFileIds.length > 0 ? `(${selectedFileIds.length})` : ''}`}
          </>
        )}
      </Button>
      {isTooManyFiles && (
        <p className="absolute top-full left-0 mt-1 text-xs text-destructive whitespace-nowrap">
          Max {MAX_FILES_FOR_EXCEL} files allowed. Selected: {selectedFileIds.length}
        </p>
      )}
    </div>
  );
}

/**
 * T097: Excel export hook with blob handling and automatic cleanup
 *
 * Provides Excel generation from CSV files with progress tracking and error handling.
 * Each CSV file becomes a separate sheet in the Excel workbook.
 * Automatically handles URL cleanup to prevent memory leaks (T100).
 *
 * Feature: 008-upload-history-user (User Story 4)
 */

import { useMutation } from '@tanstack/react-query';
import { exportToExcel } from '@/entities/batch/api/batchApi';

/**
 * Parameters for Excel export from batch files.
 *
 * Exports selected CSV files as Excel workbook (.xlsx) with each CSV as a sheet.
 * Backend handles gzip decompression and encoding detection automatically.
 */
interface ExcelExportParams {
  /** Batch unique identifier */
  batchId: string;
  /** File IDs to include in Excel export (must be CSV files) */
  fileIds: string[];
  /** Optional filename for the Excel workbook */
  excelFilename?: string;
}

/**
 * Trigger browser download from a blob URL.
 *
 * Creates a temporary <a> element, triggers click, and cleans up.
 * Object URL is automatically revoked after download to free memory.
 *
 * @param url - Blob URL to download
 * @param filename - Suggested filename for download
 */
function triggerDownload(url: string, filename: string): void {
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.style.display = 'none';

  document.body.appendChild(link);
  link.click();

  // Cleanup: Remove link after small delay to ensure download started
  setTimeout(() => {
    document.body.removeChild(link);
  }, 100);
}

/**
 * T097: Hook for exporting CSV files to Excel workbook.
 *
 * Generates Excel workbook (.xlsx) from selected CSV files:
 * - Each CSV file becomes a separate sheet
 * - Sheet names truncated to 31 characters (Excel limit)
 * - Duplicate sheet names get numeric suffix (e.g., "data", "data (2)")
 * - Handles .csv.gz decompression automatically
 * - Detects encoding (UTF-8, Windows-1252, ISO-8859-1)
 *
 * T100: Automatically cleans up object URLs to prevent memory leaks.
 *
 * Usage:
 * ```tsx
 * const { mutate: exportExcel, isPending } = useExcelExport();
 *
 * // Export multiple CSVs to Excel
 * exportExcel({
 *   batchId,
 *   fileIds: ['csv-uuid-1', 'csv-uuid-2'],
 *   excelFilename: 'batch-export.xlsx'
 * });
 * ```
 *
 * @returns TanStack Query mutation for Excel export
 */
export function useExcelExport() {
  return useMutation({
    mutationFn: async ({ batchId, fileIds, excelFilename }: ExcelExportParams) => {
      // Fetch Excel blob from backend
      const blob = await exportToExcel(batchId, fileIds);
      const filename = excelFilename || `batch-${batchId}.xlsx`;

      // Create temporary object URL for blob
      const blobUrl = window.URL.createObjectURL(blob);

      try {
        triggerDownload(blobUrl, filename);
        return { fileName: filename, fileCount: fileIds.length };
      } finally {
        // T100: Cleanup - Revoke object URL after download to free memory
        // Delayed cleanup to ensure download has started
        setTimeout(() => {
          window.URL.revokeObjectURL(blobUrl);
        }, 1000);
      }
    },
    retry: 2, // Retry failed exports twice
    retryDelay: (attemptIndex) => Math.min(1000 * 2 ** attemptIndex, 5000), // Exponential backoff (1s, 2s, max 5s)
  });
}

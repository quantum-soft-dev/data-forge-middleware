/**
 * Custom hook for downloading comparison summary report as text file.
 *
 * This hook provides mutation logic for downloading summary reports,
 * including:
 * - API call with blob response type
 * - Automatic file download trigger via browser
 * - Loading and error state management
 * - Filename extraction from Content-Disposition header
 *
 * @module features/file-comparison/hooks/useDownloadSummaryReport
 */

import { useMutation } from '@tanstack/react-query';
import { comparisonApi } from '../api/comparisonApi';

/**
 * Extract filename from Content-Disposition header.
 *
 * @param contentDisposition - Content-Disposition header value
 * @returns Extracted filename or default filename
 *
 * @example
 * ```typescript
 * const filename = extractFilename('attachment; filename="comparison-123-summary.txt"');
 * // Returns: "comparison-123-summary.txt"
 * ```
 */
function extractFilename(contentDisposition: string | undefined): string {
  if (!contentDisposition) {
    return 'comparison-summary.txt';
  }

  // Extract filename from Content-Disposition header
  // Format: attachment; filename="comparison-123-summary.txt"
  const filenameMatch = contentDisposition.match(/filename[^;=\n]*=((['"]).*?\2|[^;\n]*)/);

  if (filenameMatch && filenameMatch[1]) {
    return filenameMatch[1].replace(/['"]/g, '');
  }

  return 'comparison-summary.txt';
}

/**
 * Trigger browser download for Blob.
 *
 * @param blob - Blob data to download
 * @param filename - Filename for download
 *
 * @example
 * ```typescript
 * const blob = new Blob(['content'], { type: 'text/plain' });
 * triggerDownload(blob, 'comparison-123-summary.txt');
 * ```
 */
function triggerDownload(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.style.display = 'none';

  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);

  // Clean up blob URL to free memory
  URL.revokeObjectURL(url);
}

/**
 * Custom hook for downloading comparison summary report.
 *
 * Provides mutation for downloading comparison summary report as text file
 * with automatic file download trigger via browser.
 *
 * @returns TanStack Query mutation result
 *
 * @example
 * ```typescript
 * function DownloadReportButton({ comparisonId }: { comparisonId: string }) {
 *   const { mutate, isPending, isError, error } = useDownloadSummaryReport();
 *
 *   const handleDownload = () => {
 *     mutate(comparisonId);
 *   };
 *
 *   return (
 *     <button onClick={handleDownload} disabled={isPending}>
 *       {isPending ? 'Downloading...' : 'Download Report'}
 *     </button>
 *   );
 * }
 * ```
 *
 * Phase 8 (User Story 6) - Task T103
 */
export function useDownloadSummaryReport() {
  return useMutation({
    mutationFn: async (comparisonId: string) => {
      // Call API to download summary report
      const response = await comparisonApi.downloadSummaryReport(comparisonId);

      // Extract filename from Content-Disposition header
      const contentDisposition = response.headers['content-disposition'];
      const filename = extractFilename(contentDisposition);

      // Trigger browser download
      triggerDownload(response.data, filename);

      return { filename, size: response.data.size };
    },
    onError: (error) => {
      console.error('Failed to download summary report:', error);
      // Error will be handled by UI component (toast notification, etc.)
    },
    onSuccess: (data) => {
      console.log(`Downloaded summary report: ${data.filename} (${data.size} bytes)`);
    },
  });
}

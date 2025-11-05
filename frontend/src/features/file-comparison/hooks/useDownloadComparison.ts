/**
 * Custom hook for downloading comparison results as ZIP archive.
 *
 * This hook provides mutation logic for downloading comparison results,
 * including:
 * - API call with blob response type
 * - Automatic file download trigger via browser
 * - Loading and error state management
 * - Filename extraction from Content-Disposition header
 *
 * @module features/file-comparison/hooks/useDownloadComparison
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
 * const filename = extractFilename('attachment; filename="comparison-123.zip"');
 * // Returns: "comparison-123.zip"
 * ```
 */
function extractFilename(contentDisposition: string | undefined): string {
  if (!contentDisposition) {
    return 'comparison-download.zip';
  }

  // Extract filename from Content-Disposition header
  // Format: attachment; filename="comparison-123.zip"
  const filenameMatch = contentDisposition.match(/filename[^;=\n]*=((['"]).*?\2|[^;\n]*)/);

  if (filenameMatch && filenameMatch[1]) {
    return filenameMatch[1].replace(/['"]/g, '');
  }

  return 'comparison-download.zip';
}

/**
 * Trigger browser download for Blob.
 *
 * @param blob - Blob data to download
 * @param filename - Filename for download
 *
 * @example
 * ```typescript
 * const blob = new Blob(['content'], { type: 'application/zip' });
 * triggerDownload(blob, 'comparison-123.zip');
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
 * Custom hook for downloading comparison results.
 *
 * Provides mutation for downloading comparison ZIP archive with automatic
 * file download trigger via browser.
 *
 * @returns TanStack Query mutation result
 *
 * @example
 * ```typescript
 * function DownloadButton({ comparisonId }: { comparisonId: string }) {
 *   const { mutate, isPending, isError, error } = useDownloadComparison();
 *
 *   const handleDownload = () => {
 *     mutate(comparisonId);
 *   };
 *
 *   return (
 *     <button onClick={handleDownload} disabled={isPending}>
 *       {isPending ? 'Downloading...' : 'Download ZIP'}
 *     </button>
 *   );
 * }
 * ```
 *
 * Phase 7 (User Story 4) - Task T095
 */
export function useDownloadComparison() {
  return useMutation({
    mutationFn: async (comparisonId: string) => {
      // Call API to download comparison
      const response = await comparisonApi.downloadComparison(comparisonId);

      // Extract filename from Content-Disposition header
      const contentDisposition = response.headers['content-disposition'];
      const filename = extractFilename(contentDisposition);

      // Trigger browser download
      triggerDownload(response.data, filename);

      return { filename, size: response.data.size };
    },
    onError: (error) => {
      console.error('Failed to download comparison:', error);
      // Error will be handled by UI component (toast notification, etc.)
    },
    onSuccess: (data) => {
      console.log(`Downloaded comparison: ${data.filename} (${data.size} bytes)`);
    },
  });
}

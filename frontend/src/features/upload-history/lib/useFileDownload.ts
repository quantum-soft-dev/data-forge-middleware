/**
 * T075: File download hook with blob handling and automatic cleanup
 *
 * Provides unified download logic for both single files (presigned S3 URLs)
 * and multiple files (ZIP archives). Handles blob creation, browser download
 * trigger, and URL cleanup to prevent memory leaks.
 *
 * Feature: 008-upload-history-user (User Story 3)
 */

import { useMutation } from '@tanstack/react-query';
import { downloadFile, downloadFilesAsZip } from '@/entities/batch/api/batchApi';

/**
 * Parameters for downloading files from a batch.
 *
 * Single file download uses presigned S3 URLs (15-minute expiry).
 * Multiple files are streamed as ZIP archive from backend.
 */
interface DownloadParams {
  /** Batch unique identifier */
  batchId: string;
  /** File IDs to download (single ID for direct download, multiple for ZIP) */
  fileIds: string[];
  /** Optional filename override for ZIP archives */
  zipFilename?: string;
}

/**
 * Trigger browser download from a blob or URL.
 *
 * Creates a temporary <a> element, triggers click, and cleans up.
 * For presigned URLs, browser downloads directly from S3.
 * For blobs, creates object URL and revokes after download.
 *
 * @param url - Blob URL or presigned S3 URL
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
 * T075: Hook for downloading files from upload history.
 *
 * Handles two download scenarios:
 * 1. Single file: Fetches presigned S3 URL, triggers direct download
 * 2. Multiple files: Fetches ZIP blob, creates object URL, triggers download
 *
 * Automatically cleans up object URLs to prevent memory leaks (T078).
 *
 * Usage:
 * ```tsx
 * const { mutate: download, isPending } = useFileDownload();
 *
 * // Single file download
 * download({ batchId, fileIds: ['file-uuid'] });
 *
 * // Multiple files as ZIP
 * download({ batchId, fileIds: ['uuid1', 'uuid2'], zipFilename: 'batch-files.zip' });
 * ```
 *
 * @returns TanStack Query mutation for file downloads
 */
export function useFileDownload() {
  return useMutation({
    mutationFn: async ({ batchId, fileIds, zipFilename }: DownloadParams) => {
      // Single file: Use presigned S3 URL (no server bandwidth consumption)
      if (fileIds.length === 1) {
        const { downloadUrl, fileName } = await downloadFile(batchId, fileIds[0]);
        triggerDownload(downloadUrl, fileName);
        return { fileName, type: 'single' as const };
      }

      // Multiple files: Download as ZIP blob
      const blob = await downloadFilesAsZip(batchId, fileIds);
      const filename = zipFilename || `batch-${batchId}.zip`;

      // Create temporary object URL for blob
      const blobUrl = window.URL.createObjectURL(blob);

      try {
        triggerDownload(blobUrl, filename);
        return { fileName: filename, type: 'zip' as const };
      } finally {
        // T078: Cleanup - Revoke object URL after download to free memory
        // Delayed cleanup to ensure download has started
        setTimeout(() => {
          window.URL.revokeObjectURL(blobUrl);
        }, 1000);
      }
    },
    retry: 2, // Retry failed downloads twice
    retryDelay: (attemptIndex) => Math.min(1000 * 2 ** attemptIndex, 5000), // Exponential backoff (1s, 2s, max 5s)
  });
}

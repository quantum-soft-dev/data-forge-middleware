/**
 * T076: Download button component for batch file downloads
 *
 * Provides unified download functionality for both single files (presigned URLs)
 * and multiple files (ZIP archives). Disabled when no files selected or batch
 * is not in a completed status (COMPLETED or COMPLETED_WITH_WARNINGS).
 *
 * Feature: 008-upload-history-user (User Story 3)
 */

import { Download, Loader2 } from 'lucide-react';
import { Button } from '@/shared/ui/ui/button';
import { useFileDownload } from '../lib/useFileDownload';
import { toast } from 'sonner';

interface DownloadButtonProps {
  /** Batch unique identifier */
  batchId: string;
  /** Selected file IDs to download */
  selectedFileIds: string[];
  /** Batch status (only COMPLETED batches allow downloads) */
  batchStatus: string;
  /** Optional custom ZIP filename */
  zipFilename?: string;
  /** Optional custom button text */
  children?: React.ReactNode;
}

/**
 * T076: Download button with conditional ZIP/single file logic
 *
 * Behavior:
 * - Disabled when no files selected or batch status is not completed (COMPLETED/COMPLETED_WITH_WARNINGS)
 * - Single file: Downloads directly via presigned S3 URL (15-minute expiry)
 * - Multiple files: Downloads as ZIP archive (streamed from backend)
 *
 * Usage:
 * ```tsx
 * <DownloadButton
 *   batchId={batch.id}
 *   selectedFileIds={selectedFileIds}
 *   batchStatus={batch.status}
 *   zipFilename="batch-files.zip"
 * />
 * ```
 */
export function DownloadButton({
  batchId,
  selectedFileIds,
  batchStatus,
  zipFilename,
  children,
}: DownloadButtonProps) {
  const { mutate: download, isPending } = useFileDownload();

  // Calculate button state - enabled if files selected (regardless of batch status)
  const isDisabled = selectedFileIds.length === 0 || isPending;

  console.log('[DownloadButton] Props:', {
    batchId,
    selectedFileIds,
    batchStatus,
    isPending,
    isDisabled,
  });

  // Handle download click
  const handleDownload = () => {
    download(
      {
        batchId,
        fileIds: selectedFileIds,
        zipFilename,
      },
      {
        onSuccess: (data) => {
          toast.success(
            `Download started: ${data.fileName}`,
            {
              description: `${selectedFileIds.length} file(s) - ${data.type === 'zip' ? 'ZIP archive' : 'Direct download'}`,
            }
          );
        },
        onError: (error) => {
          toast.error('Download failed', {
            description:
              error instanceof Error
                ? error.message
                : 'Failed to download files. Please try again.',
          });
        },
      }
    );
  };

  return (
    <Button
      onClick={handleDownload}
      disabled={isDisabled}
      variant="default"
      className="gap-2"
    >
      {isPending ? (
        <>
          <Loader2 className="h-4 w-4 animate-spin" />
          Downloading...
        </>
      ) : (
        <>
          <Download className="h-4 w-4" />
          {children || `Download ${selectedFileIds.length > 0 ? `(${selectedFileIds.length})` : ''}`}
        </>
      )}
    </Button>
  );
}

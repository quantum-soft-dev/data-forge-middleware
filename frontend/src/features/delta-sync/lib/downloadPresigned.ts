/**
 * Shared presign-then-download flow (feature 025) — used by the checkpoint
 * file pills (DeltaSyncWidget) and the batch delta Parquet pills
 * (DeltaBatchDetail), so the UX (popup-safe trigger, toasts, error taxonomy)
 * cannot drift between the two.
 */

import { isAxiosError } from 'axios';
import { toast } from 'sonner';

export interface PresignedDownloadLike {
  downloadUrl: string;
  fileName: string;
}

export interface OpenPresignedDownloadOptions {
  /** Shown for a 404 (file genuinely absent); other failures get a generic retry toast. */
  notFoundMessage?: string;
}

/**
 * Mint a fresh presigned URL and start the download.
 *
 * The download is triggered by a same-tab anchor click instead of
 * `window.open`: after an `await` the call is outside the user-gesture window
 * and Safari/strict popup settings silently drop new tabs. The presigned URL
 * carries `response-content-disposition=attachment`, so same-tab navigation
 * downloads without leaving the page.
 */
export async function openPresignedDownload(
  presign: () => Promise<PresignedDownloadLike>,
  { notFoundMessage }: OpenPresignedDownloadOptions = {},
): Promise<void> {
  try {
    const download = await presign();

    const anchor = document.createElement('a');
    anchor.href = download.downloadUrl;
    anchor.rel = 'noopener';
    anchor.download = download.fileName;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();

    toast.success('Download link generated · valid 15 minutes');
  } catch (error) {
    if (notFoundMessage && isAxiosError(error) && error.response?.status === 404) {
      toast.error(notFoundMessage);
      return;
    }
    toast.error('Something went wrong. Please try again.');
  }
}

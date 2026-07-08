/**
 * Shared presign-then-download flow (feature 025) — used by the checkpoint
 * file pills (DeltaSyncWidget) and the batch delta Parquet pills
 * (DeltaBatchDetail), so the UX (popup-safe trigger, toasts, error taxonomy)
 * cannot drift between the two.
 */

import { isAxiosError } from 'axios';
import { toast } from 'sonner';
import { getServerErrorMessage } from '@/shared/api/error-handler';

export interface PresignedDownloadLike {
  downloadUrl: string;
  fileName: string;
}

export interface OpenPresignedDownloadOptions {
  /**
   * Shown for a 404 (file genuinely absent). Other failures surface the
   * server's `message` when present (e.g. the 503 "storage unavailable"),
   * falling back to a generic retry toast. The presign requests suppress the
   * global error toast, so this taxonomy is the user's only signal.
   */
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
    if (isAxiosError(error)) {
      // The server message wins even on 404 — the status is overloaded (missing file,
      // but also site-not-found / wrong scope), and the backend is more precise than
      // the caller's file-not-built hint. notFoundMessage covers the bodyless 404 the
      // presign endpoints answer for a genuinely absent file.
      const serverMessage = getServerErrorMessage(error);
      if (serverMessage) {
        toast.error(serverMessage);
        return;
      }
      if (notFoundMessage && error.response?.status === 404) {
        toast.error(notFoundMessage);
        return;
      }
    }
    toast.error('Something went wrong. Please try again.');
  }
}

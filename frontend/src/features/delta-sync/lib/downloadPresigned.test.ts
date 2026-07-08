/**
 * openPresignedDownload error taxonomy: the presign requests suppress the
 * global toast (suppressErrorToast), so this flow is the ONLY place the user
 * learns why a download failed — it must surface the server's message
 * (e.g. the 503 "Object storage is temporarily unavailable"), not collapse
 * everything into a generic retry toast.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { toast } from 'sonner';
import { openPresignedDownload } from './downloadPresigned';

vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
    success: vi.fn(),
  },
}));

function axios404(message?: string): AxiosError {
  return makeAxiosError(404, message);
}

function makeAxiosError(status: number, message?: string): AxiosError {
  const config = {} as InternalAxiosRequestConfig;
  const response = {
    data: message ? { message } : {},
    status,
    statusText: '',
    headers: {},
    config,
  } as AxiosResponse;
  return new AxiosError(`Request failed with status code ${status}`, 'ERR_BAD_REQUEST', config, null, response);
}

describe('openPresignedDownload', () => {
  beforeEach(() => {
    vi.mocked(toast.error).mockClear();
    vi.mocked(toast.success).mockClear();
  });

  it('downloads via a same-tab anchor and toasts success', async () => {
    const anchorClick = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});

    await openPresignedDownload(async () => ({ downloadUrl: 'https://s3/x', fileName: 'x.parquet' }));

    expect(anchorClick).toHaveBeenCalled();
    expect(toast.success).toHaveBeenCalledWith('Download link generated · valid 15 minutes');
    anchorClick.mockRestore();
  });

  it('prefers the server message for a 404 — the status is overloaded (review r3)', async () => {
    // A presign 404 can also mean site-not-found / wrong scope: the accurate server
    // message must win over the caller's file-not-built hint.
    await openPresignedDownload(
      async () => { throw axios404('Site not found'); },
      { notFoundMessage: 'No delta Parquet — no declared schema.' },
    );

    expect(toast.error).toHaveBeenCalledWith('Site not found');
  });

  it('falls back to the caller notFoundMessage for a bodyless 404', async () => {
    // The presign endpoints answer a missing file with an empty 404 body — the
    // caller's hint is the only useful diagnosis there.
    await openPresignedDownload(
      async () => { throw axios404(); },
      { notFoundMessage: 'No delta Parquet — no declared schema.' },
    );

    expect(toast.error).toHaveBeenCalledWith('No delta Parquet — no declared schema.');
  });

  it('falls back to the server message for a 404 without notFoundMessage', async () => {
    await openPresignedDownload(async () => { throw axios404('Checkpoint file not found'); });

    expect(toast.error).toHaveBeenCalledWith('Checkpoint file not found');
  });

  it('surfaces the server message for a 503 (storage unavailable)', async () => {
    await openPresignedDownload(
      async () => { throw makeAxiosError(503, 'Object storage is temporarily unavailable. Please try again.'); },
      { notFoundMessage: 'No delta Parquet — no declared schema.' },
    );

    expect(toast.error).toHaveBeenCalledWith('Object storage is temporarily unavailable. Please try again.');
  });

  it('stays generic when the failure carries no server message', async () => {
    await openPresignedDownload(async () => { throw new Error('network down'); });

    expect(toast.error).toHaveBeenCalledWith('Something went wrong. Please try again.');
  });
});

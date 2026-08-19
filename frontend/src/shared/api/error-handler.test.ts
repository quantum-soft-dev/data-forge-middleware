/**
 * Global error toast interceptor. Two properties, both about this handler not
 * adding a toast of its own: callers that render their own error taxonomy
 * (e.g. openPresignedDownload) opt out via `suppressErrorToast` in the request
 * config, and registration is idempotent — App.tsx sets the handler up from an
 * effect that re-runs as auth settles, so a second call must replace the first
 * interceptor rather than stack a second one behind it.
 */

import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { toast } from 'sonner';
import { apiClient } from './client';
import { setupErrorHandler } from './error-handler';

vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
    success: vi.fn(),
  },
}));

/** Adapter that makes any request fail as a 404 without a network. */
const notFoundAdapter = async (config: InternalAxiosRequestConfig) => {
  const response = {
    data: { message: 'Parquet file not found' },
    status: 404,
    statusText: 'Not Found',
    headers: {},
    config,
  } as AxiosResponse;
  throw new AxiosError(
    'Request failed with status code 404',
    AxiosError.ERR_BAD_REQUEST,
    config,
    null,
    response,
  );
};

describe('setupErrorHandler', () => {
  beforeAll(() => {
    setupErrorHandler();
  });

  beforeEach(() => {
    vi.mocked(toast.error).mockClear();
  });

  it('registers one interceptor however often it is set up', async () => {
    // beforeAll already registered once; App.tsx's effect re-runs as `isLoading`
    // and `isAuthenticated` settle, and `getAccessTokenSilently` changes identity.
    setupErrorHandler();
    setupErrorHandler();

    await expect(
      apiClient.get('/anything', { adapter: notFoundAdapter }),
    ).rejects.toThrow();

    expect(toast.error).toHaveBeenCalledTimes(1);
  });

  it('toasts HTTP errors by default', async () => {
    await expect(
      apiClient.get('/anything', { adapter: notFoundAdapter }),
    ).rejects.toThrow();

    expect(toast.error).toHaveBeenCalledWith('Parquet file not found');
  });

  it('stays silent when the request opts out via suppressErrorToast', async () => {
    await expect(
      apiClient.get('/anything', { adapter: notFoundAdapter, suppressErrorToast: true }),
    ).rejects.toThrow();

    expect(toast.error).not.toHaveBeenCalled();
  });
});

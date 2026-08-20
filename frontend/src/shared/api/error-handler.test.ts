/**
 * Global error toast interceptor. Two properties, both about this handler not
 * adding a toast of its own: callers that render their own error taxonomy
 * (e.g. openPresignedDownload) opt out via `suppressErrorToast` in the request
 * config, and registration is idempotent — App.tsx sets the handler up from an
 * effect that re-runs as auth settles, so a second call must replace the first
 * interceptor rather than stack a second one behind it.
 *
 * Both are properties of a failure that carries its own response. On the 401 and
 * token-refresh paths this handler still speaks out of turn — four routes, none of
 * them about registration count; see the note in error-handler.ts.
 */

import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { toast } from 'sonner';
import { apiClient } from './client';
import { clearErrorHandler, setupErrorHandler } from './error-handler';
import { clearResponseInterceptor, setupResponseInterceptor } from './interceptors';
import { initTokenRefresh, resetTokenRefreshState } from './token-refresh';

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

/**
 * The handler sits *behind* the 401 refresh interceptor, which is the order
 * App.tsx registers them in. It matters, and it is a consequence of the eject:
 * before it, the refresh interceptor re-registered on every effect run while
 * this one only appended, so from the second run the accumulated toast handler
 * ran **ahead** of the live refresh handler — and toasted a 401 that the refresh
 * was about to resolve. Registration count cannot show that; only the two
 * together can.
 */
describe('setupErrorHandler relative to the 401 refresh interceptor', () => {
  /** 401 once, then 200 — i.e. a session the refresh genuinely repairs. */
  const refreshableAdapter = () => {
    let calls = 0;
    return async (config: InternalAxiosRequestConfig) => {
      calls += 1;
      if (calls === 1) {
        const response = {
          data: {},
          status: 401,
          statusText: 'Unauthorized',
          headers: {},
          config,
        } as AxiosResponse;
        throw new AxiosError('401', AxiosError.ERR_BAD_REQUEST, config, null, response);
      }
      return { data: { ok: true }, status: 200, statusText: 'OK', headers: {}, config } as AxiosResponse;
    };
  };

  beforeEach(() => {
    vi.mocked(toast.error).mockClear();
    initTokenRefresh(async () => 'fresh-token', () => {});
    // App.tsx:53-61 order: refresh manager, then the 401 interceptor, then this one.
    setupResponseInterceptor();
    setupErrorHandler();
  });

  afterEach(() => {
    clearResponseInterceptor();
    clearErrorHandler();
    resetTokenRefreshState();
  });

  it('stays silent on a 401 the refresh repairs', async () => {
    await expect(
      apiClient.get('/anything', { adapter: refreshableAdapter() }),
    ).resolves.toMatchObject({ status: 200 });

    expect(toast.error).not.toHaveBeenCalled();
  });
});

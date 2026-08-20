/**
 * Global error toast interceptor. Two properties of a failure that carries its
 * own response: callers that render their own error taxonomy (e.g.
 * openPresignedDownload) opt out via `suppressErrorToast` in the request config,
 * and registration is idempotent — App.tsx sets the handler up from an effect
 * that re-runs as auth settles, so a second call must replace the first
 * interceptor rather than stack a second one behind it.
 *
 * 401 is a third property, and it is not about registration count (that was
 * #225). A 401 produces at most one toast, never a network message for a failure
 * that is not about the network, and `suppressErrorToast` is honoured on every
 * branch — see the four routes in the 401 describe below (issue #239).
 */

import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { toast } from 'sonner';
import { apiClient } from './client';
import { clearErrorHandler, setupErrorHandler } from './error-handler';
import { clearResponseInterceptor, setupResponseInterceptor } from './interceptors';
import { initTokenRefresh, resetTokenRefreshState } from './token-refresh';
import type { Auth0Error, GetAccessTokenFn, RetryableAxiosConfig } from './types';

/** Leftover 401s (no refresh attempted, or already retried) — never "unexpected", never network. */
const SESSION_TOAST = 'Your session is no longer valid. Please sign in again.';
const UNEXPECTED_TOAST = 'An unexpected error occurred. Please try again later.';
const NETWORK_TOAST_HANDLER = 'Network error. Please check your connection and try again.';
const NETWORK_TOAST_REFRESH = 'Network error. Please check your connection.';
const REFRESH_FAILED_TOAST = 'Failed to refresh session. Please try again.';
const AUTH0_UNAVAILABLE_TOAST = 'Authentication service unavailable. Please try again later.';

function statusAdapter(status: number, body: Record<string, unknown> = {}) {
  return async (config: InternalAxiosRequestConfig) => {
    const response = {
      data: body,
      status,
      statusText: status === 401 ? 'Unauthorized' : 'Error',
      headers: {},
      config,
    } as AxiosResponse;
    throw new AxiosError(
      `Request failed with status code ${status}`,
      AxiosError.ERR_BAD_REQUEST,
      config,
      null,
      response,
    );
  };
}

function auth0Error(code: string, message?: string): Auth0Error {
  const err = new Error(message ?? code) as Auth0Error;
  err.error = code;
  return err;
}

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

  it('toasts a leftover 401 as a session error, not as unexpected or network', async () => {
    await expect(
      apiClient.get('/anything', { adapter: statusAdapter(401) }),
    ).rejects.toThrow();

    expect(toast.error).toHaveBeenCalledTimes(1);
    expect(toast.error).toHaveBeenCalledWith(SESSION_TOAST);
    expect(toast.error).not.toHaveBeenCalledWith(UNEXPECTED_TOAST);
    expect(toast.error).not.toHaveBeenCalledWith(NETWORK_TOAST_HANDLER);
  });

  it('honours suppressErrorToast on a leftover 401', async () => {
    await expect(
      apiClient.get('/anything', { adapter: statusAdapter(401), suppressErrorToast: true }),
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
 *
 * The four 401 routes below are a different property (issue #239): they reproduce
 * with a single correctly registered handler, so no amount of registration
 * hygiene closes them. A 401 must produce at most one toast, never a network
 * message for a failure that is not about the network, and honour
 * `suppressErrorToast` on every branch.
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

  /**
   * 401, then a different status on the retry — a refresh that mints a valid
   * token against a backend that still rejects (account deactivated, role
   * revoked), or a 500 on the retried call.
   */
  const failingRetryAdapter = (retryStatus: number) => {
    let calls = 0;
    return async (config: InternalAxiosRequestConfig) => {
      calls += 1;
      const status = calls === 1 ? 401 : retryStatus;
      const response = {
        data: {},
        status,
        statusText: status === 401 ? 'Unauthorized' : 'Error',
        headers: {},
        config,
      } as AxiosResponse;
      throw new AxiosError(
        `Request failed with status code ${status}`,
        AxiosError.ERR_BAD_REQUEST,
        config,
        null,
        response,
      );
    };
  };

  afterEach(() => {
    clearResponseInterceptor();
    clearErrorHandler();
    resetTokenRefreshState();
  });

  function wireAppOrder(refresh: GetAccessTokenFn | 'none' = async () => 'fresh-token'): void {
    vi.mocked(toast.error).mockClear();
    if (refresh !== 'none') {
      initTokenRefresh(refresh, () => {});
    }
    // App.tsx:53-61 order: refresh manager, then the 401 interceptor, then this one.
    setupResponseInterceptor();
    setupErrorHandler();
  }

  it('stays silent on a 401 the refresh repairs', async () => {
    wireAppOrder();
    await expect(
      apiClient.get('/anything', { adapter: refreshableAdapter() }),
    ).resolves.toMatchObject({ status: 200 });

    expect(toast.error).not.toHaveBeenCalled();
  });

  it('route 1: leftover 401 when refresh is not initialized is one session toast', async () => {
    wireAppOrder('none');

    await expect(
      apiClient.get('/anything', { adapter: statusAdapter(401) }),
    ).rejects.toMatchObject({ response: { status: 401 } });

    expect(toast.error).toHaveBeenCalledTimes(1);
    expect(toast.error).toHaveBeenCalledWith(SESSION_TOAST);
    expect(toast.error).not.toHaveBeenCalledWith(UNEXPECTED_TOAST);
  });

  it('route 1: leftover 401 when the request was already retried is one session toast', async () => {
    wireAppOrder();

    await expect(
      apiClient.get('/anything', {
        adapter: statusAdapter(401),
        _retry: true,
      } as RetryableAxiosConfig),
    ).rejects.toMatchObject({ response: { status: 401 } });

    expect(toast.error).toHaveBeenCalledTimes(1);
    expect(toast.error).toHaveBeenCalledWith(SESSION_TOAST);
  });

  it('route 2: expired refresh token is silence, not a network toast', async () => {
    wireAppOrder(async () => {
      throw auth0Error('invalid_grant', 'Unknown or invalid refresh token.');
    });

    await expect(
      apiClient.get('/anything', { adapter: statusAdapter(401) }),
    ).rejects.toMatchObject({ response: { status: 401 } });

    expect(toast.error).not.toHaveBeenCalled();
  });

  it('route 2: a named refresh failure is that toast alone, not a network toast on top', async () => {
    wireAppOrder(async () => {
      throw new Error('something else went wrong');
    });

    await expect(
      apiClient.get('/anything', { adapter: statusAdapter(401) }),
    ).rejects.toMatchObject({ response: { status: 401 } });

    expect(toast.error).toHaveBeenCalledTimes(1);
    expect(toast.error).toHaveBeenCalledWith(REFRESH_FAILED_TOAST);
    expect(toast.error).not.toHaveBeenCalledWith(NETWORK_TOAST_HANDLER);
  });

  it('route 2: a network error during refresh is the refresh interceptor toast alone', async () => {
    wireAppOrder(async () => {
      throw new Error('Network Error');
    });

    await expect(
      apiClient.get('/anything', { adapter: statusAdapter(401) }),
    ).rejects.toMatchObject({ response: { status: 401 } });

    expect(toast.error).toHaveBeenCalledTimes(1);
    expect(toast.error).toHaveBeenCalledWith(NETWORK_TOAST_REFRESH);
    expect(toast.error).not.toHaveBeenCalledWith(NETWORK_TOAST_HANDLER);
  });

  it('route 2: Auth0 unavailable is that toast alone', async () => {
    wireAppOrder(async () => {
      throw Object.assign(new Error('temporarily unavailable'), { status: 503 });
    });

    await expect(
      apiClient.get('/anything', { adapter: statusAdapter(401) }),
    ).rejects.toMatchObject({ response: { status: 401 } });

    expect(toast.error).toHaveBeenCalledTimes(1);
    expect(toast.error).toHaveBeenCalledWith(AUTH0_UNAVAILABLE_TOAST);
  });

  it('route 3: suppressErrorToast survives a failed refresh (expired)', async () => {
    wireAppOrder(async () => {
      throw auth0Error('invalid_grant');
    });

    await expect(
      apiClient.get('/anything', { adapter: statusAdapter(401), suppressErrorToast: true }),
    ).rejects.toMatchObject({ response: { status: 401 } });

    expect(toast.error).not.toHaveBeenCalled();
  });

  it('route 3: suppressErrorToast survives a named refresh failure', async () => {
    wireAppOrder(async () => {
      throw new Error('something else went wrong');
    });

    await expect(
      apiClient.get('/anything', { adapter: statusAdapter(401), suppressErrorToast: true }),
    ).rejects.toMatchObject({ response: { status: 401 } });

    expect(toast.error).not.toHaveBeenCalled();
  });

  it('route 3: suppressErrorToast survives leftover 401 when refresh is not initialized', async () => {
    wireAppOrder('none');

    await expect(
      apiClient.get('/anything', { adapter: statusAdapter(401), suppressErrorToast: true }),
    ).rejects.toMatchObject({ response: { status: 401 } });

    expect(toast.error).not.toHaveBeenCalled();
  });

  it('route 4: refresh succeeds and the retry still 401s — one session toast, not two', async () => {
    wireAppOrder();

    await expect(
      apiClient.get('/anything', { adapter: failingRetryAdapter(401) }),
    ).rejects.toMatchObject({ response: { status: 401 } });

    expect(toast.error).toHaveBeenCalledTimes(1);
    expect(toast.error).toHaveBeenCalledWith(SESSION_TOAST);
  });

  it('route 4: refresh succeeds and the retry 500s — one server toast, not two', async () => {
    wireAppOrder();

    await expect(
      apiClient.get('/anything', { adapter: failingRetryAdapter(500) }),
    ).rejects.toMatchObject({ response: { status: 500 } });

    expect(toast.error).toHaveBeenCalledTimes(1);
    expect(toast.error).toHaveBeenCalledWith(UNEXPECTED_TOAST);
  });

  it('route 4: suppressErrorToast is honoured on a retried 401', async () => {
    wireAppOrder();

    await expect(
      apiClient.get('/anything', {
        adapter: failingRetryAdapter(401),
        suppressErrorToast: true,
      }),
    ).rejects.toMatchObject({ response: { status: 401 } });

    expect(toast.error).not.toHaveBeenCalled();
  });
});

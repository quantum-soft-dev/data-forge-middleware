import { describe, it, expect, vi, beforeEach } from 'vitest';
import { AxiosError, AxiosHeaders } from 'axios';
import { apiClient } from '@/shared/api/client';
import {
  deltaBasePath,
  getDeltaSyncState,
  getDeltaSegments,
  presignCheckpointDownload,
  presignBatchTableParquet,
  requestCheckpointRebuild,
  requestRebaseline,
  cancelRebaseline,
  getDeltaSyncHealth,
  wipeSiteHistory,
  SiteHistoryWipeConflictError,
} from './deltaSyncApi';

vi.mock('@/shared/api/client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), delete: vi.fn() },
}));

const mockedGet = vi.mocked(apiClient.get);
const mockedPost = vi.mocked(apiClient.post);
const mockedDelete = vi.mocked(apiClient.delete);

const syncStatePayload = {
  lastAppliedSeq: 4821,
  lastCheckpointSeq: 3200,
  lastCheckpointAt: null,
  schemaVersion: 12,
  updatedAt: '2026-07-05T12:30:00Z',
  rebaselineRequested: false,
  rebuildRequested: false,
};

beforeEach(() => {
  vi.clearAllMocks();
});

describe('deltaBasePath', () => {
  it('switches between owner and admin namespaces', () => {
    expect(deltaBasePath('s1', { admin: false })).toBe('/v1/account/sites/s1/delta');
    expect(deltaBasePath('s1', { admin: true })).toBe('/v1/sites/s1/delta');
  });
});

describe('getDeltaSyncState', () => {
  it('parses the payload from the owner endpoint with the global toast suppressed', async () => {
    mockedGet.mockResolvedValueOnce({ data: syncStatePayload });

    const state = await getDeltaSyncState('s1', { admin: false });

    // 404 is the NORMAL state for a never-connected site and this query polls every 20s:
    // without the opt-out the interceptor toasts "Resource not found." on every poll.
    expect(mockedGet).toHaveBeenCalledWith('/v1/account/sites/s1/delta/sync-state', {
      suppressErrorToast: true,
    });
    expect(state?.lastAppliedSeq).toBe(4821);
  });

  it('maps 404 (client never connected) to null instead of throwing', async () => {
    const error = new AxiosError('Not Found', '404', undefined, undefined, {
      status: 404,
      statusText: 'Not Found',
      data: {},
      headers: {},
      config: { headers: new AxiosHeaders() },
    });
    mockedGet.mockRejectedValueOnce(error);

    await expect(getDeltaSyncState('s1', { admin: false })).resolves.toBeNull();
  });

  it('re-throws non-404 errors', async () => {
    mockedGet.mockRejectedValueOnce(new Error('boom'));
    await expect(getDeltaSyncState('s1', { admin: false })).rejects.toThrow('boom');
  });
});

describe('admin-only and action endpoints', () => {
  it('fetches segments from the admin namespace with a limit', async () => {
    mockedGet.mockResolvedValueOnce({ data: [] });

    await getDeltaSegments('s1', 16);

    expect(mockedGet).toHaveBeenCalledWith('/v1/sites/s1/delta/segments', { params: { limit: 16 } });
  });

  it('presigns a download per click with the requested format', async () => {
    mockedGet.mockResolvedValueOnce({
      data: { downloadUrl: 'https://s3/x', fileName: 'orders_seq1.parquet', expiresAt: '2026-07-05T12:45:00Z' },
    });

    const download = await presignCheckpointDownload('s1', 'orders', 'parquet', { admin: false });

    expect(mockedGet).toHaveBeenCalledWith('/v1/account/sites/s1/delta/checkpoints/orders/download', {
      params: { format: 'parquet' },
      suppressErrorToast: true,
    });
    expect(download.fileName).toContain('.parquet');
  });

  it('presigns a batch table Parquet on the owner route with the global toast suppressed (025)', async () => {
    mockedGet.mockResolvedValueOnce({
      data: { downloadUrl: 'https://s3/x', fileName: 'orders_seq1-2.parquet', expiresAt: '2026-07-05T12:45:00Z' },
    });

    const download = await presignBatchTableParquet('s1', 'b1', 'orders/x');

    expect(mockedGet).toHaveBeenCalledWith('/v1/account/sites/s1/delta/batches/b1/tables/orders%2Fx/parquet', {
      suppressErrorToast: true,
    });
    expect(download.fileName).toContain('.parquet');
  });

  it('posts rebuild to the admin namespace and rebaseline to the scoped one', async () => {
    mockedPost.mockResolvedValue({ data: { status: 'ok' } });

    await requestCheckpointRebuild('s1');
    expect(mockedPost).toHaveBeenCalledWith('/v1/sites/s1/delta/checkpoints/rebuild');

    await requestRebaseline('s1', { admin: false });
    expect(mockedPost).toHaveBeenCalledWith('/v1/account/sites/s1/delta/rebaseline');
  });

  it('takes a re-baseline request back with DELETE on the scoped namespace', async () => {
    mockedDelete.mockResolvedValue({ data: { status: 'cancelled' } });

    expect(await cancelRebaseline('s1', { admin: false })).toBe('cancelled');
    expect(mockedDelete).toHaveBeenCalledWith('/v1/account/sites/s1/delta/rebaseline');

    await cancelRebaseline('s1', { admin: true });
    expect(mockedDelete).toHaveBeenCalledWith('/v1/sites/s1/delta/rebaseline');
  });

  it('passes the too-late statuses through unchanged (#84)', async () => {
    for (const status of ['snapshot-in-progress', 'client-notified', 'not-requested']) {
      mockedDelete.mockResolvedValue({ data: { status } });
      expect(await cancelRebaseline('s1', { admin: false })).toBe(status);
    }
  });

  it('selects the owner or admin bulk-health endpoint', async () => {
    mockedGet.mockResolvedValue({ data: [] });

    await getDeltaSyncHealth();
    expect(mockedGet).toHaveBeenCalledWith('/v1/account/sites/delta/health');

    await getDeltaSyncHealth('acc-1');
    expect(mockedGet).toHaveBeenCalledWith('/v1/accounts/acc-1/sites/delta/health');
  });
});

describe('wipeSiteHistory (#89)', () => {
  const summary = {
    generation: 4,
    deletedBatches: 123,
    deletedSegments: 45,
    deletedCheckpoints: 12,
    deletedFiles: 678,
    deletedSqlGenerations: 9,
    deletedErrorLogs: 33,
    deletedBytes: 123456789,
    s3DeleteErrors: 0,
    prefixesNotSwept: 0,
    baselineBatchDetached: false,
  };

  it('posts the confirmation to the owner namespace and parses the summary', async () => {
    mockedPost.mockResolvedValueOnce({ data: summary });

    const result = await wipeSiteHistory('s1', 'store-01.example.com', { admin: false });

    expect(mockedPost).toHaveBeenCalledWith(
      '/v1/account/sites/s1/delta/wipe',
      { confirm: 'store-01.example.com' },
      { suppressErrorToast: true },
    );
    expect(result).toEqual(summary);
  });

  it('uses the admin namespace when scoped to admin', async () => {
    mockedPost.mockResolvedValueOnce({ data: summary });

    await wipeSiteHistory('s1', 'store-01.example.com', { admin: true });

    expect(mockedPost).toHaveBeenCalledWith(
      '/v1/sites/s1/delta/wipe',
      { confirm: 'store-01.example.com' },
      { suppressErrorToast: true },
    );
  });

  it('turns a 409 into a typed conflict so the two cases can be told apart', async () => {
    // "stop the client" and "just try again" are opposite instructions; a generic error would
    // leave the operator guessing which one they are looking at.
    mockedPost.mockRejectedValueOnce(
      new AxiosError('conflict', undefined, undefined, undefined, {
        status: 409,
        data: { status: 'session-in-progress' },
        statusText: 'Conflict',
        headers: new AxiosHeaders(),
        config: { headers: new AxiosHeaders() },
      }),
    );

    await expect(wipeSiteHistory('s1', 'x', { admin: false })).rejects.toMatchObject({
      name: 'SiteHistoryWipeConflictError',
      status: 'session-in-progress',
    });
  });

  it('reports the retryable conflict distinctly', async () => {
    mockedPost.mockRejectedValueOnce(
      new AxiosError('conflict', undefined, undefined, undefined, {
        status: 409,
        data: { status: 'concurrent-session' },
        statusText: 'Conflict',
        headers: new AxiosHeaders(),
        config: { headers: new AxiosHeaders() },
      }),
    );

    await expect(wipeSiteHistory('s1', 'x', { admin: false })).rejects.toBeInstanceOf(
      SiteHistoryWipeConflictError,
    );
  });

  it('lets other failures through untouched', async () => {
    mockedPost.mockRejectedValueOnce(
      new AxiosError('bad request', undefined, undefined, undefined, {
        status: 400,
        data: { message: 'Confirmation must be the site\'s name' },
        statusText: 'Bad Request',
        headers: new AxiosHeaders(),
        config: { headers: new AxiosHeaders() },
      }),
    );

    await expect(wipeSiteHistory('s1', 'wrong', { admin: false })).rejects.not.toBeInstanceOf(
      SiteHistoryWipeConflictError,
    );
  });
});

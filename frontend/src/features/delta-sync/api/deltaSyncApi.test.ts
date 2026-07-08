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
  getDeltaSyncHealth,
} from './deltaSyncApi';

vi.mock('@/shared/api/client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn() },
}));

const mockedGet = vi.mocked(apiClient.get);
const mockedPost = vi.mocked(apiClient.post);

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
  it('parses the payload from the owner endpoint', async () => {
    mockedGet.mockResolvedValueOnce({ data: syncStatePayload });

    const state = await getDeltaSyncState('s1', { admin: false });

    expect(mockedGet).toHaveBeenCalledWith('/v1/account/sites/s1/delta/sync-state');
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

  it('selects the owner or admin bulk-health endpoint', async () => {
    mockedGet.mockResolvedValue({ data: [] });

    await getDeltaSyncHealth();
    expect(mockedGet).toHaveBeenCalledWith('/v1/account/sites/delta/health');

    await getDeltaSyncHealth('acc-1');
    expect(mockedGet).toHaveBeenCalledWith('/v1/accounts/acc-1/sites/delta/health');
  });
});

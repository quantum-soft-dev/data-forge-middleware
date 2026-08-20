/**
 * Device authorization API client — request shape.
 *
 * The verification lookup is the one call on this page whose failures the page
 * itself renders as a tailored taxonomy (400 "already processed", 404 "not
 * found or expired"), so it must not also reach the global error interceptor
 * (issue #211).
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { apiClient } from '@/shared/api/client';
import { getVerifyInfo, approveAuthorization, denyAuthorization } from './deviceAuthApi';

vi.mock('@/shared/api/client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), delete: vi.fn() },
}));

const mockedGet = vi.mocked(apiClient.get);
const mockedPost = vi.mocked(apiClient.post);

const verifyInfo = {
  userCode: 'M9Q2-4AML',
  siteName: 'pg_test_extractor',
  siteDescription: null,
  expiresAt: '2026-08-18T12:47:00Z',
};

beforeEach(() => {
  vi.clearAllMocks();
});

describe('getVerifyInfo', () => {
  it('asks for the code with the global error toast suppressed', async () => {
    mockedGet.mockResolvedValueOnce({ data: verifyInfo });

    const info = await getVerifyInfo('M9Q2-4AML');

    expect(mockedGet).toHaveBeenCalledWith('/v1/device/verify?code=M9Q2-4AML', {
      suppressErrorToast: true,
    });
    expect(info).toEqual(verifyInfo);
  });
});

describe('approveAuthorization / denyAuthorization', () => {
  it('post the action for the code', async () => {
    mockedPost.mockResolvedValue({ data: { success: true, siteId: 's1', siteName: 'pg' } });

    await approveAuthorization('M9Q2-4AML');
    expect(mockedPost).toHaveBeenLastCalledWith('/v1/device/verify', {
      userCode: 'M9Q2-4AML',
      action: 'approve',
    });

    await denyAuthorization('M9Q2-4AML');
    expect(mockedPost).toHaveBeenLastCalledWith('/v1/device/verify', {
      userCode: 'M9Q2-4AML',
      action: 'deny',
    });
  });
});

/**
 * Client diagnostic logs API client.
 *
 * Feature: 021-unified-upload-api (T042, US11)
 */

import { apiClient } from '@/shared/api/client';
import {
  ADMIN_SITES_CLIENT_LOGS,
  ADMIN_SITES_CLIENT_LOG_DOWNLOAD,
} from '@/shared/api/apiRoutes';
import type { ClientLogListResponse, ClientLogDownloadResponse } from '../model/types';

/**
 * List client diagnostic logs for a site.
 *
 * GET /api/v1/admin/sites/{siteId}/client-logs
 */
export async function listClientLogs(
  siteId: string,
  page: number = 0,
  size: number = 20,
): Promise<ClientLogListResponse> {
  const response = await apiClient.get<ClientLogListResponse>(
    ADMIN_SITES_CLIENT_LOGS(siteId),
    { params: { page, size } },
  );
  return response.data;
}

/**
 * Get presigned download URL for a client log file.
 *
 * GET /api/v1/admin/sites/{siteId}/client-logs/{logId}/download
 */
export async function downloadClientLog(
  siteId: string,
  logId: string,
): Promise<ClientLogDownloadResponse> {
  const response = await apiClient.get<ClientLogDownloadResponse>(
    ADMIN_SITES_CLIENT_LOG_DOWNLOAD(siteId, logId),
  );
  return response.data;
}

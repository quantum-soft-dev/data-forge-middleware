/**
 * T035: Batch API client for upload history endpoints
 *
 * Provides type-safe API methods for viewing batch history.
 * Uses axios instance from shared/api/client.
 *
 * Feature: 008-upload-history-user (User Story 1)
 */

import { apiClient } from '@/shared/api/client';
import type {
  BatchSummary,
  BatchDetail,
  CursorPageResponse
} from '../model/types';

/**
 * T035: List batch history for current user with cursor-based pagination.
 *
 * GET /api/user/batches?cursor={cursor}&limit={limit}
 *
 * Uses Keycloak OAuth2 authentication. Returns batches filtered by user's account.
 *
 * @param cursor - Cursor for next page (undefined for first page)
 * @param limit - Maximum items per page (default 20, max 100)
 * @returns Paginated batch list with cursor for next page
 */
export async function listBatches(
  cursor?: string,
  limit?: number
): Promise<CursorPageResponse<BatchSummary>> {
  const params = new URLSearchParams();

  if (cursor) {
    params.append('cursor', cursor);
  }

  if (limit !== undefined) {
    params.append('limit', limit.toString());
  }

  const response = await apiClient.get<CursorPageResponse<BatchSummary>>(
    `/user/batches?${params.toString()}`
  );

  return response.data;
}

/**
 * T052: Get batch details with file list.
 *
 * GET /api/user/batches/{batchId}
 *
 * Returns detailed batch information including all uploaded files.
 * Throws 403 if batch doesn't belong to user, 404 if batch doesn't exist.
 *
 * @param batchId - Batch unique identifier (UUID)
 * @returns Batch details with file list
 */
export async function getBatchDetails(batchId: string): Promise<BatchDetail> {
  const response = await apiClient.get<BatchDetail>(`/user/batches/${batchId}`);
  return response.data;
}

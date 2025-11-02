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

/**
 * T073: Download a single file from a batch.
 *
 * GET /api/user/batches/{batchId}/files/{fileId}/download
 *
 * Returns file download metadata with presigned S3 URL (15-minute expiry).
 * The frontend uses this URL to trigger browser download directly from S3.
 *
 * @param batchId - Batch unique identifier (UUID)
 * @param fileId - File unique identifier (UUID)
 * @returns Download metadata with presigned URL
 */
export async function downloadFile(
  batchId: string,
  fileId: string
): Promise<{ downloadUrl: string; fileName: string; fileSize: number; expiresAt: string }> {
  const response = await apiClient.get<{
    downloadUrl: string;
    fileName: string;
    fileSize: number;
    expiresAt: string;
  }>(`/user/batches/${batchId}/files/${fileId}/download`);
  return response.data;
}

/**
 * T074: Download multiple files as a ZIP archive.
 *
 * POST /api/user/batches/{batchId}/download-zip
 *
 * Streams multiple files as a ZIP archive. For single file, use downloadFile() instead.
 * The ZIP is generated server-side using Apache Commons Compress with streaming.
 *
 * @param batchId - Batch unique identifier (UUID)
 * @param fileIds - Array of file IDs to include in ZIP
 * @returns Blob containing the ZIP archive
 */
export async function downloadFilesAsZip(
  batchId: string,
  fileIds: string[]
): Promise<Blob> {
  const response = await apiClient.post(
    `/user/batches/${batchId}/download-zip`,
    { fileIds },
    {
      responseType: 'blob',
      headers: {
        'Content-Type': 'application/json',
      },
    }
  );
  return response.data;
}

/**
 * T096: Export selected CSV files to Excel workbook (.xlsx).
 *
 * POST /api/user/batches/{batchId}/export-excel
 *
 * Generates Excel workbook with each CSV file as a separate sheet.
 * Uses Apache POI SXSSF for memory-efficient streaming (100-row window).
 * Handles gzip decompression and encoding detection (UTF-8/Windows-1252).
 *
 * @param batchId - Batch unique identifier (UUID)
 * @param fileIds - Array of CSV file IDs to convert to Excel sheets
 * @returns Blob containing the Excel workbook (.xlsx)
 */
export async function exportToExcel(
  batchId: string,
  fileIds: string[]
): Promise<Blob> {
  const response = await apiClient.post(
    `/user/batches/${batchId}/export-excel`,
    { fileIds },
    {
      responseType: 'blob',
      headers: {
        'Content-Type': 'application/json',
      },
    }
  );
  return response.data;
}

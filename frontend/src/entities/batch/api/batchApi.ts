/**
 * T035: Batch API client for upload history endpoints
 *
 * Provides type-safe API methods for viewing batch history.
 * Uses axios instance from shared/api/client.
 *
 * Feature: 008-upload-history-user (User Story 1)
 */

import { apiClient } from '@/shared/api/client';
import {
  HISTORY_BATCHES,
  HISTORY_BATCH_ID,
  HISTORY_FILE_DOWNLOAD,
  HISTORY_ZIP_DOWNLOAD,
  HISTORY_EXCEL_EXPORT,
  HISTORY_ERRORS
} from '@/shared/api/apiRoutes';
import type {
  BatchSummary,
  BatchDetail,
  CursorPageResponse,
  PageResponse,
  ErrorSummary
} from '../model/types';

/**
 * T035: List batch history for current user with cursor-based pagination.
 *
 * GET /api/v1/history/batches?cursor={cursor}&limit={limit}
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
    `${HISTORY_BATCHES}?${params.toString()}`
  );

  return response.data;
}

/**
 * T052: Get batch details with file list.
 *
 * GET /api/v1/history/batches/{batchId}
 *
 * Returns detailed batch information including all uploaded files.
 * Throws 403 if batch doesn't belong to user, 404 if batch doesn't exist.
 *
 * @param batchId - Batch unique identifier (UUID)
 * @returns Batch details with file list
 */
export async function getBatchDetails(batchId: string): Promise<BatchDetail> {
  const response = await apiClient.get<BatchDetail>(HISTORY_BATCH_ID(batchId));
  return response.data;
}

/**
 * T073: Download a single file from a batch.
 *
 * GET /api/v1/history/batches/{batchId}/files/{fileId}/download
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
  }>(HISTORY_FILE_DOWNLOAD(batchId, fileId));
  return response.data;
}

/**
 * T074: Download multiple files as a ZIP archive.
 *
 * POST /api/v1/history/batches/{batchId}/download-zip
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
    HISTORY_ZIP_DOWNLOAD(batchId),
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
 * POST /api/v1/history/batches/{batchId}/export-excel
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
    HISTORY_EXCEL_EXPORT(batchId),
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
 * T105: Get errors for batch with offset-based pagination (Phase 7).
 *
 * GET /api/v1/history/batches/{batchId}/errors?page={page}&size={size}
 *
 * Returns paginated list of errors for a specific batch.
 * Authorization: Verifies batch belongs to user's account (throws 403 if unauthorized).
 * Errors sorted by occurredAt DESC (newest first).
 *
 * @param batchId - Batch unique identifier (UUID)
 * @param page - Page number (0-indexed, default 0)
 * @param size - Page size (default 20, max 100)
 * @returns Paginated error list
 * @throws 403 Forbidden if batch doesn't belong to user
 * @throws 404 Not Found if batch doesn't exist
 */
export async function getBatchErrors(
  batchId: string,
  page: number = 0,
  size: number = 20
): Promise<PageResponse<ErrorSummary>> {
  const params = new URLSearchParams();
  params.append('page', page.toString());
  params.append('size', size.toString());

  const response = await apiClient.get<PageResponse<ErrorSummary>>(
    `${HISTORY_ERRORS(batchId)}?${params.toString()}`
  );

  return response.data;
}

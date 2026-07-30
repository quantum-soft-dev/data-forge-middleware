/**
 * Delta Sync API client (023, F4).
 *
 * Owner and admin surfaces share every function: `admin` switches between
 * /v1/account/sites/{siteId}/delta (owner, ownership enforced server-side) and
 * /v1/sites/{siteId}/delta (ROLE_ADMIN). Responses are Zod-parsed.
 */

import { isAxiosError } from 'axios';
import { apiClient } from '@/shared/api/client';
import {
  cancelRebaselineResultSchema,
  deltaCheckpointDownloadSchema,
  deltaCheckpointSchema,
  deltaSegmentSchema,
  deltaSyncHealthSchema,
  deltaSyncStateSchema,
  type CancelRebaselineStatus,
  type DeltaCheckpoint,
  type DeltaCheckpointDownload,
  type DeltaCheckpointFormat,
  type DeltaSegment,
  type DeltaSyncHealth,
  type DeltaSyncState,
} from '../model/types';

export interface DeltaApiScope {
  /** True for the admin surface (/v1/sites/...), false for the owner surface. */
  admin: boolean;
}

export function deltaBasePath(siteId: string, { admin }: DeltaApiScope): string {
  return admin ? `/v1/sites/${siteId}/delta` : `/v1/account/sites/${siteId}/delta`;
}

/**
 * Current sync state of a site, or null when the client has never connected
 * (the backend answers 404 — the UI renders the "No sync activity yet" state).
 */
export async function getDeltaSyncState(siteId: string, scope: DeltaApiScope): Promise<DeltaSyncState | null> {
  try {
    // 404 is the normal state for a never-connected site and this query polls every 20s —
    // the global toast must stay silent (the widget renders empty/error states inline).
    const response = await apiClient.get(`${deltaBasePath(siteId, scope)}/sync-state`, {
      suppressErrorToast: true,
    });
    return deltaSyncStateSchema.parse(response.data);
  } catch (error) {
    if (isAxiosError(error) && error.response?.status === 404) {
      return null;
    }
    throw error;
  }
}

/** Per-table checkpoints, sorted by table name server-side. */
export async function getDeltaCheckpoints(siteId: string, scope: DeltaApiScope): Promise<DeltaCheckpoint[]> {
  const response = await apiClient.get(`${deltaBasePath(siteId, scope)}/checkpoints`);
  return deltaCheckpointSchema.array().parse(response.data);
}

/** Fresh presigned download URL for one checkpoint file — requested per click, never cached. */
export async function presignCheckpointDownload(
  siteId: string,
  tableName: string,
  format: DeltaCheckpointFormat,
  scope: DeltaApiScope,
): Promise<DeltaCheckpointDownload> {
  const response = await apiClient.get(
    `${deltaBasePath(siteId, scope)}/checkpoints/${encodeURIComponent(tableName)}/download`,
    // openPresignedDownload shows its own error taxonomy — no global toast.
    { params: { format }, suppressErrorToast: true },
  );
  return deltaCheckpointDownloadSchema.parse(response.data);
}

/**
 * Fresh presigned URL for one table's delta Parquet file of a batch (feature 025) — per click.
 * Owner route only: batch detail has no admin surface (the admin twin was descoped).
 */
export async function presignBatchTableParquet(
  siteId: string,
  batchId: string,
  tableName: string,
): Promise<DeltaCheckpointDownload> {
  const response = await apiClient.get(
    `${deltaBasePath(siteId, { admin: false })}/batches/${batchId}/tables/${encodeURIComponent(tableName)}/parquet`,
    // openPresignedDownload shows its own error taxonomy — no global toast.
    { suppressErrorToast: true },
  );
  return deltaCheckpointDownloadSchema.parse(response.data);
}

/** Recent changelog segments, newest first. Admin surface only (P2 pending). */
export async function getDeltaSegments(siteId: string, limit = 20): Promise<DeltaSegment[]> {
  const response = await apiClient.get(`${deltaBasePath(siteId, { admin: true })}/segments`, {
    params: { limit },
  });
  return deltaSegmentSchema.array().parse(response.data);
}

/** Queue a forced out-of-schedule checkpoint rebuild (admin only). */
export async function requestCheckpointRebuild(siteId: string): Promise<void> {
  await apiClient.post(`${deltaBasePath(siteId, { admin: true })}/checkpoints/rebuild`);
}

/** Request a full re-baseline on the client's next connect (owner + admin). */
export async function requestRebaseline(siteId: string, scope: DeltaApiScope): Promise<void> {
  await apiClient.post(`${deltaBasePath(siteId, scope)}/rebaseline`);
}

/**
 * Take a pending re-baseline request back (#84) — the watermark, checkpoints and segments are
 * untouched, so the client resumes ordinary delta. Idempotent server-side; the returned status
 * says what the cancellation achieved, since neither a snapshot in flight (`session-in-progress`)
 * nor an already-committed one (`not-requested`) can be called off.
 */
export async function cancelRebaseline(
  siteId: string,
  scope: DeltaApiScope,
): Promise<CancelRebaselineStatus> {
  const response = await apiClient.delete(`${deltaBasePath(siteId, scope)}/rebaseline`);
  return cancelRebaselineResultSchema.parse(response.data).status;
}

/**
 * Bulk sync health for all V2 sites of an account — one request per poll.
 * Owner form when accountId is omitted; admin form otherwise.
 */
export async function getDeltaSyncHealth(accountId?: string): Promise<DeltaSyncHealth[]> {
  const path = accountId
    ? `/v1/accounts/${accountId}/sites/delta/health`
    : '/v1/account/sites/delta/health';
  const response = await apiClient.get(path);
  return deltaSyncHealthSchema.array().parse(response.data);
}

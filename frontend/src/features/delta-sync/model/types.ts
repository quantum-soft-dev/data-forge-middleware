/**
 * Delta Sync DTO schemas (023, F4).
 *
 * Zod schemas mirror the backend REST DTOs (B4–B10); API clients parse every
 * response so shape drift fails loudly in development.
 */

import { z } from 'zod';

/** GET .../delta/sync-state (B4). */
export const deltaSyncStateSchema = z.object({
  lastAppliedSeq: z.number(),
  lastCheckpointSeq: z.number(),
  lastCheckpointAt: z.string().nullable(),
  schemaVersion: z.number(),
  updatedAt: z.string(),
  rebaselineRequested: z.boolean(),
  rebuildRequested: z.boolean(),
});
export type DeltaSyncState = z.infer<typeof deltaSyncStateSchema>;

/** GET .../delta/checkpoints (B5) — one row per table. */
export const deltaCheckpointSchema = z.object({
  table: z.string(),
  seq: z.number(),
  rowCount: z.number(),
  updatedAt: z.string(),
  hasCsv: z.boolean(),
  hasParquet: z.boolean(),
});
export type DeltaCheckpoint = z.infer<typeof deltaCheckpointSchema>;

/** GET .../delta/checkpoints/{table}/download (B5). */
export const deltaCheckpointDownloadSchema = z.object({
  downloadUrl: z.string(),
  fileName: z.string(),
  expiresAt: z.string(),
});
export type DeltaCheckpointDownload = z.infer<typeof deltaCheckpointDownloadSchema>;

export type DeltaCheckpointFormat = 'csv' | 'parquet';

/** Session mode of a changelog segment / delta batch. */
export const deltaSessionModeSchema = z.enum(['DELTA', 'CONTINUOUS', 'FULL_SNAPSHOT']);
export type DeltaSessionMode = z.infer<typeof deltaSessionModeSchema>;

/** GET /v1/sites/{siteId}/delta/segments (B6, admin only). */
export const deltaSegmentSchema = z.object({
  firstSeq: z.number(),
  lastSeq: z.number(),
  recordCount: z.number(),
  // Free-form String on the backend (proto SessionMode.name() pass-through): a strict enum
  // would reject the WHOLE segments array over one unknown value (review r3). Unknown modes
  // render on a neutral chip.
  mode: z.string(),
  createdAt: z.string(),
});
export type DeltaSegment = z.infer<typeof deltaSegmentSchema>;

/**
 * DELETE .../delta/rebaseline (#84). `not-requested` means nothing was pending anymore — the
 * client has already started its full snapshot, so the cancellation came too late.
 */
export const cancelRebaselineResultSchema = z.object({
  status: z.enum(['cancelled', 'not-requested']),
});
export type CancelRebaselineStatus = z.infer<typeof cancelRebaselineResultSchema>['status'];

/** GET .../sites/delta/health (B10) — one entry per V2 site of the account. */
export const deltaSyncHealthSchema = z.object({
  siteId: z.string(),
  hasSyncState: z.boolean(),
  lastAppliedSeq: z.number().nullable(),
  lastCheckpointSeq: z.number().nullable(),
  updatedAt: z.string().nullable(),
});
export type DeltaSyncHealth = z.infer<typeof deltaSyncHealthSchema>;

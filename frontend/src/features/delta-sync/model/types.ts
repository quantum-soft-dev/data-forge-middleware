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
  /** A FULL_SNAPSHOT session is uploading right now (#84) — outlives rebaselineRequested, which the
   * snapshot only consumes when it commits. Optional so an older backend still parses. */
  snapshotInProgress: z.boolean().optional().default(false),
  /**
   * How the last *finished* forced checkpoint rebuild ended (#186): COMPLETED, FAILED,
   * FRAME_UNAVAILABLE, DEFERRED, DISCARDED or NOTHING_TO_REBUILD. Deliberately a plain string
   * rather than a z.enum, for the same
   * reason `deltaSegmentSchema.mode` is (review r3): this object drives the whole Delta Sync tab,
   * and a value added on the server must degrade to an unrecognised chip rather than failing the
   * parse and blanking the tab. Null while no rebuild has finished — and also while one is queued
   * whose predecessor never ran, since an ending that keeps rebuildRequested records nothing.
   */
  lastRebuildOutcome: z.string().nullable().optional().default(null),
  lastRebuildOutcomeAt: z.string().nullable().optional().default(null),
  /** Operator-facing explanation; null for a rebuild that completed. */
  lastRebuildMessage: z.string().nullable().optional().default(null),
});
export type DeltaSyncState = z.infer<typeof deltaSyncStateSchema>;

/** GET .../delta/checkpoints (B5) — one row per table. */
export const deltaCheckpointSchema = z.object({
  table: z.string(),
  seq: z.number(),
  rowCount: z.number(),
  updatedAt: z.string(),
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

/** The only materialized checkpoint format: the CSV snapshot was retired with issue #113. */
export type DeltaCheckpointFormat = 'parquet';

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
 * DELETE .../delta/rebaseline (#84). Only `cancelled` means the full re-upload was averted:
 * `snapshot-in-progress` means one is uploading right now and keeps its own intent,
 * `client-notified` means the client already holds NEED_REBASELINE and may start at any moment,
 * and `not-requested` means nothing was pending (already committed, another operator, never asked).
 */
export const cancelRebaselineResultSchema = z.object({
  status: z.enum(['cancelled', 'snapshot-in-progress', 'client-notified', 'not-requested']),
});
export type CancelRebaselineStatus = z.infer<typeof cancelRebaselineResultSchema>['status'];

/** POST .../delta/wipe (035, issue #89) — what the wipe destroyed. */
export const siteHistoryWipeResultSchema = z.object({
  generation: z.number(),
  deletedBatches: z.number(),
  deletedSegments: z.number(),
  deletedCheckpoints: z.number(),
  deletedFiles: z.number(),
  deletedSqlGenerations: z.number(),
  deletedErrorLogs: z.number(),
  deletedBytes: z.number(),
  s3DeleteErrors: z.number(),
  prefixesNotSwept: z.number(),
  baselineBatchDetached: z.boolean(),
});
export type SiteHistoryWipeResult = z.infer<typeof siteHistoryWipeResultSchema>;

/** 409 body of a refused wipe — the two cases need different advice. */
export const siteHistoryWipeConflictSchema = z.object({
  status: z.enum(['session-in-progress', 'concurrent-session']),
});
export type SiteHistoryWipeConflict = z.infer<typeof siteHistoryWipeConflictSchema>['status'];

/** GET .../sites/delta/health (B10) — one entry per V2 site of the account. */
export const deltaSyncHealthSchema = z.object({
  siteId: z.string(),
  hasSyncState: z.boolean(),
  lastAppliedSeq: z.number().nullable(),
  lastCheckpointSeq: z.number().nullable(),
  updatedAt: z.string().nullable(),
});
export type DeltaSyncHealth = z.infer<typeof deltaSyncHealthSchema>;

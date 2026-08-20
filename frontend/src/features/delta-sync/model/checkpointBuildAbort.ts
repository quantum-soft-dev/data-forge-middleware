/**
 * How the last scheduled checkpoint build aborted before writing anything (issue #224).
 *
 * The backend enum is `CheckpointBuildAbort`; the values are a wire contract. The DTO field is
 * kept a plain string in the Zod schema on purpose — a strict enum would reject the whole
 * sync-state payload over one value added on the server, blanking the Delta Sync tab — so this
 * mapping has to answer for a value it has never seen as well.
 */

import type { SyncSeverity } from './severity';

export interface CheckpointBuildAbortDescription {
  /** Chip / note text. */
  label: string;
  /** Which severity token the reason is painted with. */
  severity: SyncSeverity;
}

const KNOWN: Record<string, CheckpointBuildAbortDescription> = {
  FAILED: { label: 'Build failed', severity: 'critical' },
  FOLD_TOO_LARGE: { label: 'Fold too large', severity: 'critical' },
  FRAME_TOO_LARGE: { label: 'Frame too large', severity: 'critical' },
  // Contention, not a fact about the site — the next tick tries again (#150).
  SCRATCH_FULL: { label: 'Scratch full', severity: 'elevated' },
  FRAME_UNAVAILABLE: { label: 'Could not read frame', severity: 'critical' },
  // Repairs itself once the neighbouring build finishes (#178).
  DEFERRED: { label: 'Build deferred', severity: 'elevated' },
};

/**
 * Describe a recorded scheduled-build abort, or null when the site has none.
 *
 * @param abort the raw value from the sync-state / health projection
 */
export function describeCheckpointBuildAbort(
  abort: string | null | undefined,
): CheckpointBuildAbortDescription | null {
  if (!abort) return null;
  return KNOWN[abort] ?? { label: `Build: ${abort}`, severity: 'elevated' };
}

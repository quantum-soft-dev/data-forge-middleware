/**
 * How the last finished forced checkpoint rebuild ended (issue #186).
 *
 * The backend enum is `CheckpointRebuildOutcome`; the values are a wire contract. The DTO field is
 * kept a plain string in the Zod schema on purpose — a strict enum would reject the whole
 * sync-state payload over one value added on the server, blanking the Delta Sync tab — so this
 * mapping has to answer for a value it has never seen as well.
 */

import type { SyncSeverity } from './severity';

export interface RebuildOutcomeDescription {
  /** Chip text. */
  label: string;
  /** Which severity token the chip is painted with. */
  severity: SyncSeverity;
}

const KNOWN: Record<string, RebuildOutcomeDescription> = {
  COMPLETED: { label: 'Rebuilt', severity: 'healthy' },
  FAILED: { label: 'Rebuild failed', severity: 'critical' },
  // The remedy is a bucket policy or IAM grant rather than another click (#157), which is why it
  // is not folded into FAILED: the same chip would send the operator to retry a thing that cannot
  // succeed until the permission is restored.
  FRAME_UNAVAILABLE: { label: 'Rebuild could not start', severity: 'critical' },
  // Repairs itself once the neighbouring build finishes (#178) — a prompt, not an alarm.
  DEFERRED: { label: 'Rebuild deferred', severity: 'elevated' },
};

/**
 * Describe a recorded rebuild outcome, or null when the site has none.
 *
 * @param outcome the raw value from the sync-state projection
 */
export function describeRebuildOutcome(
  outcome: string | null | undefined,
): RebuildOutcomeDescription | null {
  if (!outcome) return null;
  return KNOWN[outcome] ?? { label: `Rebuild: ${outcome}`, severity: 'elevated' };
}

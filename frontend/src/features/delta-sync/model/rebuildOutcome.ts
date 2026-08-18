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
  // The site's baseline was replaced under the build (#136/#142). Routine, and the next build
  // starts from the new baseline — so a prompt to ask again, not a failure.
  DISCARDED: { label: 'Rebuild discarded', severity: 'elevated' },
  // No frame and no segments: there was no source to rebuild from, which is a statement about the
  // site rather than about the attempt.
  NOTHING_TO_REBUILD: { label: 'Nothing to rebuild', severity: 'elevated' },
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

/**
 * Has a checkpoint been built since the verdict was recorded?
 *
 * Only a forced rebuild ever writes a verdict, so a FAILED one would otherwise paint a critical
 * chip for ever, surviving every later nightly build that succeeded (raised in review). A
 * checkpoint recorded *after* the verdict is exact evidence that whatever the rebuild ran into has
 * since cleared, so the chip keeps its label, its time and its message and stops shouting.
 *
 * This is a **one-way** signal, and deliberately so: `lastCheckpointAt` moves only when a build
 * advances the pointer, so an idle site whose nightly rematerialize quietly repaired everything
 * keeps its loud chip. That is not a hole to plug with a staleness threshold — the chip's own
 * message says to request the rebuild again, and doing so writes a fresh verdict, which is the
 * clearing mechanism. This only spares the operator the round trip when the answer is already
 * in the payload.
 *
 * @param state the sync-state projection
 */
export function isRebuildOutcomeSuperseded(state: {
  lastCheckpointAt: string | null;
  lastRebuildOutcomeAt: string | null;
}): boolean {
  if (!state.lastCheckpointAt || !state.lastRebuildOutcomeAt) return false;
  return new Date(state.lastCheckpointAt) > new Date(state.lastRebuildOutcomeAt);
}

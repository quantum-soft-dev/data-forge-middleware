/**
 * Sync severity model (023, F4) — per design handoff v2.
 *
 * Lag = lastAppliedSeq − lastCheckpointSeq (unmaterialized records).
 * Thresholds: Healthy < 1,000 · Elevated 1,000–10,000 · Critical > 10,000.
 * "Stalled" (no sync-state update for > 24 h) is a separate state that
 * overrides the lag color.
 */

export type SyncSeverity = 'healthy' | 'elevated' | 'critical' | 'stalled';

export const LAG_ELEVATED_THRESHOLD = 1_000;
export const LAG_CRITICAL_THRESHOLD = 10_000;
export const STALLED_AFTER_MS = 24 * 60 * 60 * 1000;

/** Upper bound of the lag track's square-root scale. */
export const LAG_TRACK_MAX = 20_000;

export function computeLag(state: { lastAppliedSeq: number; lastCheckpointSeq: number }): number {
  return Math.max(0, state.lastAppliedSeq - state.lastCheckpointSeq);
}

export function isStalled(updatedAtIso: string, now: Date = new Date()): boolean {
  return now.getTime() - new Date(updatedAtIso).getTime() > STALLED_AFTER_MS;
}

export function getSyncSeverity(lag: number, updatedAtIso: string, now: Date = new Date()): SyncSeverity {
  if (isStalled(updatedAtIso, now)) return 'stalled';
  if (lag > LAG_CRITICAL_THRESHOLD) return 'critical';
  if (lag >= LAG_ELEVATED_THRESHOLD) return 'elevated';
  return 'healthy';
}

/**
 * Position on the lag track in percent (square-root scale, max 20,000).
 * The 1k threshold tick sits at ~22.4%, the 10k tick at ~70.7%.
 */
export function lagTrackPercent(lag: number): number {
  const clamped = Math.min(Math.max(lag, 0), LAG_TRACK_MAX);
  return Math.sqrt(clamped / LAG_TRACK_MAX) * 100;
}

/** Compact lag label for the site-list pill: 999 → "999", 2_300 → "2.3k". */
export function formatLagShort(lag: number): string {
  if (lag >= 1_000) {
    return `${(lag / 1_000).toFixed(1)}k`;
  }
  return String(lag);
}

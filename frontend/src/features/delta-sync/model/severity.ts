/**
 * Sync severity model (023, F4) — per design handoff v2.
 *
 * Lag = lastAppliedSeq − lastCheckpointSeq (unmaterialized records).
 * Thresholds: Healthy < 1,000 · Elevated 1,000–10,000 · Critical > 10,000.
 * "Stalled" (no sync-state update for > 24 h) is a separate state that
 * overrides the lag color.
 */

import { monitoringTokens, severityTokens, type SeverityToken } from '@/shared/ui/tokens';
import { describeCheckpointBuildAbort } from './checkpointBuildAbort';

export type SyncSeverity = 'healthy' | 'elevated' | 'critical' | 'stalled';

/**
 * What a sync surface reports — a lag verdict, or the state that has no lag verdict at all.
 *
 * `awaiting-first-checkpoint` is issue #213: checkpoints are produced by one nightly cron
 * (`CheckpointScheduler`) and by an operator-forced rebuild, so a site ingested during the day has
 * none until that tick fires. Every record it has applied then counts as lag against a pointer of
 * zero, and a freshly ingested site read as "Elevated — 1,155 records behind checkpoint": a
 * designed wait rendered as a backlog alarm. It is a distinct state rather than a lag band because
 * there is nothing for it to be behind. `first-checkpoint-failed` is issue #224: the same pointer
 * of zero after a scheduled visit that produced nothing — no longer painted as that wait.
 */
export type SyncStatus = SyncSeverity | 'awaiting-first-checkpoint' | 'first-checkpoint-failed';

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

/**
 * Has this site ever had a checkpoint built?
 *
 * Zero is the canonical "none": it is the initial value of `site_sync_state.last_checkpoint_seq`
 * and what a history wipe and a re-baseline reset it to, and the backend applies the same test
 * before seeding a build from a frame. The bulk health projection carries null for the same thing.
 *
 * @param state anything carrying the checkpoint pointer
 */
export function hasCheckpoint(state: { lastCheckpointSeq: number | null }): boolean {
  return (state.lastCheckpointSeq ?? 0) > 0;
}

/**
 * The verdict a surface shows for a site (issue #213).
 *
 * Order matters twice. "Stalled" wins over the pending checkpoint deliberately: it is a statement
 * about the client having gone quiet for a day, which is both more actionable and independent of
 * whether a checkpoint exists. And the pending state wins over every lag band, because a lag
 * measured against a pointer of zero is not a backlog.
 *
 * It says *no checkpoint exists*, not *the build is healthy*. Since issue #224 a scheduled visit
 * that produced nothing leaves `lastCheckpointBuildAbort` on the projection, so a first build that
 * keeps failing is no longer the same payload as a site ingested this afternoon — that is
 * `first-checkpoint-failed`, not this wait. It is deliberately not bounded by lag magnitude — a
 * first FULL_SNAPSHOT is unbounded, so that would report the largest sites as critical on day one,
 * which is the defect #213 exists to remove.
 *
 * @param state the sync-state projection or a bulk health entry
 * @param now   injectable clock
 */
export function getSyncStatus(
  state: {
    lastAppliedSeq: number;
    lastCheckpointSeq: number | null;
    updatedAt: string;
    lastCheckpointBuildAbort?: string | null;
  },
  now: Date = new Date(),
): SyncStatus {
  if (isStalled(state.updatedAt, now)) return 'stalled';
  const lag = computeLag({
    lastAppliedSeq: state.lastAppliedSeq,
    lastCheckpointSeq: state.lastCheckpointSeq ?? 0,
  });
  // Nothing applied means nothing is waiting to be checkpointed — and no build is coming either:
  // `CheckpointScheduler` visits the union of "has segments" and "has an unmaterialized checkpoint
  // row", and an all-zero row (what a wipe leaves, and what a re-baseline requested for a client
  // that never connected creates) is on neither list. Claiming a scheduled build for it would be a
  // promise nothing keeps (review r1).
  if (!hasCheckpoint(state) && lag > 0) {
    // A recorded abort is the fact that a scheduled visit already passed this site by (#224).
    // Without it the wait is still the right reading — the first nightly build has not come
    // round yet.
    if (state.lastCheckpointBuildAbort) return 'first-checkpoint-failed';
    return 'awaiting-first-checkpoint';
  }
  return getSyncSeverity(lag, state.updatedAt, now);
}

/**
 * Neutral tone for the one status that is neither healthy nor a problem. Grey rather than green:
 * "nothing is wrong" and "everything is materialized" are different claims, and only the first is
 * true here.
 */
const AWAITING_FIRST_CHECKPOINT_TONE: SeverityToken = {
  dot: monitoringTokens.textMuted,
  text: monitoringTokens.textSecondary,
  bg: monitoringTokens.subtleBg,
  label: 'No checkpoint yet',
};

/**
 * Alarm tone for a first checkpoint the scheduled build already tried and failed to produce
 * (issue #224). Critical for a refusal that never repairs itself (`FOLD_TOO_LARGE`, …);
 * elevated for contention (`DEFERRED`, `SCRATCH_FULL`) — #213 already refused to call a
 * fold-budget miss "the build is failing". The wait's grey is gone either way.
 */
const FIRST_CHECKPOINT_FAILED_TONE: SeverityToken = {
  ...severityTokens.critical,
  label: 'Checkpoint failed',
};

/**
 * Chip colors and label for a status, so every sync surface paints the same verdict the same way.
 *
 * @param status the verdict from {@link getSyncStatus}
 * @param abort  `lastCheckpointBuildAbort` when {@code status} is `first-checkpoint-failed`
 */
export function syncStatusTone(status: SyncStatus, abort?: string | null): SeverityToken {
  if (status === 'awaiting-first-checkpoint') return AWAITING_FIRST_CHECKPOINT_TONE;
  if (status === 'first-checkpoint-failed') {
    const described = describeCheckpointBuildAbort(abort);
    if (!described) return FIRST_CHECKPOINT_FAILED_TONE;
    return {
      ...severityTokens[described.severity],
      label: described.severity === 'critical' ? 'Checkpoint failed' : described.label,
    };
  }
  return severityTokens[status];
}

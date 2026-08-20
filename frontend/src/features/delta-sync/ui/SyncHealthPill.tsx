/**
 * SyncHealthPill — compact sync-health badge for the site list (023, F11).
 *
 * Design handoff v2 §3, fed by the bulk health endpoint (B10):
 * Healthy "Synced · lag 12" · Elevated "Lag 2.3k" · Critical "Lag 18.2k" ·
 * Stalled "Stalled · 26 h" · no sync row → grey "No sync yet" · a site whose first checkpoint has
 * not been built yet → grey "No checkpoint · 1.2k" (#213), since its lag is measured against zero ·
 * a site whose first scheduled build already aborted → "Checkpoint failed · 1.2k" (#224).
 * While the bulk data is loading nothing renders (no skeleton pill — D5).
 */

import type { DeltaSyncHealth } from '../model/types';
import { formatLagShort, getSyncStatus, syncStatusTone } from '../model/severity';
import { monitoringTokens as t } from '@/shared/ui/tokens';

interface SyncHealthPillProps {
  /** Bulk health entry for this site; undefined while data is absent. */
  health?: DeltaSyncHealth;
  /** True while the bulk health query has not resolved yet — renders nothing. */
  isLoading: boolean;
  /** Injectable clock for deterministic tests. */
  now?: Date;
}

export function SyncHealthPill({ health, isLoading, now = new Date() }: SyncHealthPillProps) {
  if (isLoading) {
    return null;
  }

  if (!health || !health.hasSyncState || health.lastAppliedSeq === null || health.updatedAt === null) {
    return (
      <span
        className="inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium"
        style={{ background: t.subtleBg, color: t.textSecondary }}
        data-testid="sync-health-pill"
      >
        <span className="h-1.5 w-1.5 rounded-full" style={{ background: t.textMuted }} />
        No sync yet
      </span>
    );
  }

  const lag = Math.max(0, health.lastAppliedSeq - (health.lastCheckpointSeq ?? 0));
  const status = getSyncStatus(
    {
      lastAppliedSeq: health.lastAppliedSeq,
      lastCheckpointSeq: health.lastCheckpointSeq,
      updatedAt: health.updatedAt,
      lastCheckpointBuildAbort: health.lastCheckpointBuildAbort,
    },
    now,
  );
  const sev = syncStatusTone(status);

  const label =
    status === 'stalled'
      ? `Stalled · ${Math.round((now.getTime() - new Date(health.updatedAt).getTime()) / 3_600_000)} h`
      // A pointer of zero makes every applied record read as lag, so this site used to arrive in
      // the list wearing an amber "Lag 1.2k" for a checkpoint that is not due yet (#213). The tone
      // changes and the count does not: how much is waiting is exactly what this pill is for.
      : status === 'first-checkpoint-failed'
        ? `Checkpoint failed · ${formatLagShort(lag)}`
        : status === 'awaiting-first-checkpoint'
          ? `No checkpoint · ${formatLagShort(lag)}`
          : status === 'healthy'
            ? `Synced · lag ${formatLagShort(lag)}`
            : `Lag ${formatLagShort(lag)}`;

  return (
    <span
      className="inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium"
      style={{ background: sev.bg, color: sev.text }}
      data-testid="sync-health-pill"
    >
      <span className="h-1.5 w-1.5 rounded-full" style={{ background: sev.dot }} />
      {label}
    </span>
  );
}

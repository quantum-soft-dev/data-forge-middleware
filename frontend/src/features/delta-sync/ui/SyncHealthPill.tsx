/**
 * SyncHealthPill — compact sync-health badge for the site list (023, F11).
 *
 * Design handoff v2 §3, fed by the bulk health endpoint (B10):
 * Healthy "Synced · lag 12" · Elevated "Lag 2.3k" · Critical "Lag 18.2k" ·
 * Stalled "Stalled · 26 h" · no sync row → grey "No sync yet".
 * While the bulk data is loading nothing renders (no skeleton pill — D5).
 */

import type { DeltaSyncHealth } from '../model/types';
import { formatLagShort, getSyncSeverity } from '../model/severity';
import { monitoringTokens as t, severityTokens } from '@/shared/ui/tokens';

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
  const severity = getSyncSeverity(lag, health.updatedAt, now);
  const sev = severityTokens[severity];

  const label =
    severity === 'stalled'
      ? `Stalled · ${Math.round((now.getTime() - new Date(health.updatedAt).getTime()) / 3_600_000)} h`
      : severity === 'healthy'
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

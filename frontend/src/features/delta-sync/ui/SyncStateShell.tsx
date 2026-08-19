/**
 * SyncStateShell — the double-layer metric card of the Delta Sync tab (023, F5).
 *
 * Per design handoff v2 §1a: outer shell #EFEFEF r16 p10, grid `1fr 340px`;
 * inner white cards r12. Left: Lag card with severity chip, 34px headline and
 * the sqrt-scaled lag track (max 20k, ticks at 1k/10k, .6s ease animation).
 * Right: Last checkpoint (+ the "Rebuild queued" chip, or the verdict of the last finished
 * forced rebuild — issue #186) and Schema version / Last activity (live pulse or
 * "Sync stalled?").
 */

import { Activity, AlertTriangle, Clock } from 'lucide-react';
import { formatDateTime, formatNumber, formatRelativeTime } from '@/shared/lib/formatters';
import type { DeltaSyncState } from '../model/types';
import {
  computeLag,
  getSyncStatus,
  isStalled,
  lagTrackPercent,
  syncStatusTone,
} from '../model/severity';
import { describeRebuildOutcome, isRebuildOutcomeSuperseded } from '../model/rebuildOutcome';
import { monitoringTokens as t, severityTokens } from '@/shared/ui/tokens';

interface SyncStateShellProps {
  state: DeltaSyncState;
  /** Injectable clock for deterministic tests. */
  now?: Date;
}

const TICK_1K = lagTrackPercent(1_000);
const TICK_10K = lagTrackPercent(10_000);

export function SyncStateShell({ state, now = new Date() }: SyncStateShellProps) {
  const lag = computeLag(state);
  const status = getSyncStatus(state, now);
  const sev = syncStatusTone(status);
  const stalled = isStalled(state.updatedAt, now);
  const fillPercent = lagTrackPercent(lag);
  // Checkpoints come from one nightly cron, so a site ingested during the day has none until it
  // fires (#213). Every applied record then counts against a pointer of zero, which the lag track
  // rendered as a backlog — a scale of "how far behind" on a site with nothing to be behind.
  const awaitingFirstCheckpoint = status === 'awaiting-first-checkpoint';
  // While the request flag is up the verdict describes the PREVIOUS attempt, so the queued chip
  // wins outright — showing both would read as "queued, and it failed" for a rebuild that has not
  // run yet (#186).
  const rebuildOutcome = state.rebuildRequested
    ? null
    : describeRebuildOutcome(state.lastRebuildOutcome);
  // Only a forced rebuild writes a verdict, so a failed one would otherwise stay critical for ever,
  // outliving every nightly build that has since succeeded. A checkpoint recorded after it is exact
  // evidence that the condition cleared, so the chip keeps its label and its time and goes quiet.
  const outcomeSuperseded = isRebuildOutcomeSuperseded(state);
  const outcomeChipStyle =
    rebuildOutcome && !outcomeSuperseded
      ? {
          background: severityTokens[rebuildOutcome.severity].bg,
          color: severityTokens[rebuildOutcome.severity].text,
        }
      : { background: t.subtleBg, color: t.textSecondary };

  return (
    <div
      className="rounded-2xl p-2.5 grid gap-2.5 md:grid-cols-[1fr_340px]"
      style={{ background: t.metricShell }}
      data-testid="sync-state-shell"
    >
      {/* Pulse keyframes for the live indicator (dfPulse in the prototype) */}
      <style>{`
        @keyframes dfPulse {
          0% { box-shadow: 0 0 0 0 rgba(22,163,74,0.35); }
          70% { box-shadow: 0 0 0 6px rgba(22,163,74,0); }
          100% { box-shadow: 0 0 0 0 rgba(22,163,74,0); }
        }
      `}</style>

      {/* Lag card */}
      <div className="rounded-xl bg-white p-4" style={{ boxShadow: t.innerCardShadow }}>
        <div className="flex items-start justify-between gap-3">
          <div className="flex items-center gap-3">
            <div
              className="flex h-9 w-9 items-center justify-center rounded-full bg-white border"
              style={{ borderColor: t.iconWellBorder, boxShadow: t.iconCircleShadow }}
            >
              <Activity className="h-4 w-4" style={{ color: t.primary }} strokeWidth={1.5} />
            </div>
            <div>
              <div className="text-[15px] font-medium" style={{ color: t.title, letterSpacing: '-0.24px' }}>
                Sync lag
              </div>
              <div className="text-xs" style={{ color: t.textMuted }}>
                Records applied by the client but not yet checkpointed
              </div>
            </div>
          </div>
          <div className="flex items-center gap-2">
            {/* The request flag is only consumed when the snapshot commits, so it stays raised
                for the whole upload — without snapshotInProgress the header would keep saying
                "scheduled" while the card says "in progress" (#84 review). */}
            {(state.rebaselineRequested || state.snapshotInProgress) && (
              <span
                className="inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium"
                style={
                  state.snapshotInProgress
                    ? { background: severityTokens.critical.bg, color: severityTokens.critical.text }
                    : { background: severityTokens.elevated.bg, color: severityTokens.elevated.text }
                }
              >
                {state.snapshotInProgress
                  ? 'Full snapshot in progress'
                  : 'Full snapshot scheduled on next connect'}
              </span>
            )}
            <span
              className="inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium"
              style={{ background: sev.bg, color: sev.text }}
              data-testid="severity-chip"
            >
              <span className="h-1.5 w-1.5 rounded-full" style={{ background: sev.dot }} />
              {sev.label}
            </span>
          </div>
        </div>

        {/* Headline */}
        <div className="mt-4 flex items-baseline gap-2">
          <span
            className="text-[34px] font-medium leading-none"
            style={{ color: sev.text, letterSpacing: '-0.42px', fontVariantNumeric: 'tabular-nums' }}
            data-testid="lag-headline"
          >
            {formatNumber(lag)}
          </span>
          <span className="text-sm" style={{ color: t.textSecondary }}>
            {awaitingFirstCheckpoint ? 'records awaiting the first checkpoint' : 'records behind checkpoint'}
          </span>
        </div>

        {/* Lag track — replaced, not recoloured, while no checkpoint exists: its bands and its
            1k/10k ticks are a scale of "how far behind", and no position on it is true here. */}
        {awaitingFirstCheckpoint ? (
          <div
            className="mt-4 rounded-lg px-3 py-2 text-[13px]"
            style={{ background: t.subtleBg, color: t.textSecondary }}
            data-testid="first-checkpoint-note"
          >
            No checkpoint has been built for this site yet — the scheduled build materializes the
            first one.
            {state.nextCheckpointBuildAt && (
              <>
                {' '}
                <span style={{ color: t.text }}>
                  First scheduled build · {formatDateTime(state.nextCheckpointBuildAt)} (
                  {formatRelativeTime(state.nextCheckpointBuildAt)})
                </span>{' '}
                {/* The state cannot age itself out — nothing persisted says how long this site has
                    been waiting — so it says what to conclude if it outlives the build it names. */}
                Still missing after that? The build is failing rather than pending.
              </>
            )}
          </div>
        ) : (
        <div className="mt-4">
          <div className="flex justify-between text-xs" style={{ color: t.textSecondary }}>
            <span style={{ fontVariantNumeric: 'tabular-nums' }}>
              Last checkpoint · seq {formatNumber(state.lastCheckpointSeq)}
            </span>
            <span style={{ fontVariantNumeric: 'tabular-nums' }}>
              Applied · seq {formatNumber(state.lastAppliedSeq)}
            </span>
          </div>
          <div className="relative mt-1.5">
            <div className="flex h-2.5 overflow-hidden rounded-full">
              <div style={{ width: `${TICK_1K}%`, background: 'rgba(22,163,74,0.10)' }} />
              <div style={{ width: `${TICK_10K - TICK_1K}%`, background: 'rgba(245,158,11,0.10)' }} />
              <div style={{ width: `${100 - TICK_10K}%`, background: 'rgba(239,68,68,0.10)' }} />
            </div>
            {/* Threshold ticks */}
            <div
              className="absolute top-0 h-2.5 w-px"
              style={{ left: `${TICK_1K}%`, background: 'rgba(0,0,0,0.18)' }}
            />
            <div
              className="absolute top-0 h-2.5 w-px"
              style={{ left: `${TICK_10K}%`, background: 'rgba(0,0,0,0.18)' }}
            />
            {/* Fill + pointer */}
            <div
              className="absolute left-0 top-0 h-2.5 rounded-full"
              style={{ width: `${fillPercent}%`, background: sev.dot, transition: 'width .6s ease' }}
              data-testid="lag-fill"
            />
            <div
              className="absolute top-1/2 h-3.5 w-3.5 -translate-y-1/2 -translate-x-1/2 rounded-full bg-white"
              style={{
                left: `${fillPercent}%`,
                border: `3px solid ${sev.dot}`,
                transition: 'left .6s ease',
              }}
              data-testid="lag-pointer"
            />
          </div>
          <div className="relative mt-1 h-4 text-[11px]" style={{ color: t.textMuted }}>
            <span className="absolute -translate-x-1/2" style={{ left: `${TICK_1K}%` }}>
              1k · watch
            </span>
            <span className="absolute -translate-x-1/2" style={{ left: `${TICK_10K}%` }}>
              10k · critical
            </span>
          </div>
        </div>
        )}
      </div>

      {/* Right column */}
      <div className="grid gap-2.5">
        {/* Last checkpoint */}
        <div className="rounded-xl bg-white p-4" style={{ boxShadow: t.innerCardShadow }}>
          <div className="flex items-center justify-between">
            <div className="text-[13px] font-medium" style={{ color: t.textSecondary }}>
              Last checkpoint
            </div>
            {state.rebuildRequested && (
              <span
                className="rounded-full px-2 py-0.5 text-xs font-medium"
                style={{ background: t.blue50, color: t.primary }}
              >
                Rebuild queued
              </span>
            )}
            {rebuildOutcome && (
              <span
                className="rounded-full px-2 py-0.5 text-xs font-medium"
                style={outcomeChipStyle}
                data-testid="rebuild-outcome-chip"
              >
                {rebuildOutcome.label}
              </span>
            )}
          </div>
          <div
            className="mt-1 text-[22px] font-medium"
            style={{ color: t.text, letterSpacing: '-0.33px', fontVariantNumeric: 'tabular-nums' }}
          >
            seq {formatNumber(state.lastCheckpointSeq)}
          </div>
          <div className="mt-1 flex items-center gap-1 text-[13px]" style={{ color: t.textSecondary }}>
            <Clock className="h-3.5 w-3.5" strokeWidth={1.5} />
            {state.lastCheckpointAt ? formatRelativeTime(state.lastCheckpointAt) : 'never built'}
          </div>
          {awaitingFirstCheckpoint && state.nextCheckpointBuildAt && (
            <div className="mt-1 text-[12px]" style={{ color: t.textMuted }}>
              first scheduled build {formatRelativeTime(state.nextCheckpointBuildAt)}
            </div>
          )}
          {rebuildOutcome && (
            <div className="mt-3 border-t pt-2 text-[12px]" style={{ borderColor: t.separator }}>
              <div style={{ color: t.textSecondary }}>
                Forced rebuild
                {state.lastRebuildOutcomeAt
                  ? ` · ${formatRelativeTime(state.lastRebuildOutcomeAt)}`
                  : ''}
              </div>
              {state.lastRebuildMessage && (
                // Clamped rather than truncated: the message is the failure's own text (up to a
                // kilobyte) and is the operator's whole diagnosis, so the full string stays
                // available on hover instead of being cut server-side.
                <p
                  className="mt-0.5 line-clamp-4 break-words"
                  style={{ color: t.textMuted }}
                  title={state.lastRebuildMessage}
                  data-testid="rebuild-outcome-message"
                >
                  {state.lastRebuildMessage}
                </p>
              )}
            </div>
          )}
        </div>

        {/* Schema version + last activity */}
        <div className="rounded-xl bg-white p-4" style={{ boxShadow: t.innerCardShadow }}>
          <div className="text-[13px] font-medium" style={{ color: t.textSecondary }}>
            Schema version
          </div>
          {/* No Schema Viewer surface exists in the product yet, so no "View schema" link (§6.1). */}
          <div
            className="mt-1 text-[22px] font-medium"
            style={{ color: t.text, letterSpacing: '-0.33px', fontVariantNumeric: 'tabular-nums' }}
          >
            v {state.schemaVersion}
          </div>
          <div className="mt-3 border-t pt-3" style={{ borderColor: t.separator }}>
            <div className="text-[13px] font-medium" style={{ color: t.textSecondary }}>
              Last activity
            </div>
            {stalled ? (
              <span
                className="mt-1 inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium"
                style={{ background: severityTokens.stalled.bg, color: severityTokens.stalled.text }}
              >
                <AlertTriangle className="h-3.5 w-3.5" strokeWidth={1.5} />
                Sync stalled?
              </span>
            ) : (
              <div className="mt-1 flex items-center gap-2 text-[13px]" style={{ color: t.textSecondary }}>
                <span
                  className="h-[7px] w-[7px] rounded-full"
                  style={{ background: severityTokens.healthy.dot, animation: 'dfPulse 2s infinite' }}
                />
                live · updated {formatRelativeTime(state.updatedAt)}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

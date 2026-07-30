/**
 * RebaselineCard — the full re-baseline action card (023, F9).
 *
 * Design handoff v2 §1e: visible to owner AND admin. While the persistent
 * rebaselineRequested flag is set the button is replaced by the amber
 * "Full snapshot scheduled on next connect" pill; the server consumes the flag
 * when the FULL_SNAPSHOT session commits, so it stays set for the whole upload.
 *
 * The pill is paired with a cancel action (#84) so a mis-click can be taken
 * back — and once the client is actually uploading, the card says so instead,
 * since at that point nothing can call the snapshot off.
 */

import { monitoringTokens as t, severityTokens } from '@/shared/ui/tokens';

interface RebaselineCardProps {
  /** Persistent rebaseline_requested flag from the sync-state DTO. */
  rebaselineRequested: boolean;
  /** A FULL_SNAPSHOT session is uploading right now (#84) — no longer cancellable. */
  snapshotInProgress?: boolean;
  /** Opens the confirmation dialog. */
  onRequest: () => void;
  /** Opens the cancellation dialog (only reachable while a request is pending). */
  onCancel: () => void;
  /** True while the cancellation is in flight — holds the button against double submits. */
  cancelling?: boolean;
  /**
   * False while the request itself is still in flight: cancelling then races the POST that raises
   * the flag and would be answered "nothing was pending" just before it appears (#84 review).
   */
  cancellable?: boolean;
}

function Pill({ label, tone }: { label: string; tone: 'elevated' | 'critical' }) {
  return (
    <span
      className="rounded-full px-3 py-1.5 text-xs font-medium"
      style={{ background: severityTokens[tone].bg, color: severityTokens[tone].text }}
    >
      {label}
    </span>
  );
}

export function RebaselineCard({
  rebaselineRequested,
  snapshotInProgress,
  onRequest,
  onCancel,
  cancelling,
  cancellable = true,
}: RebaselineCardProps) {
  return (
    <div
      className="flex flex-wrap items-center justify-between gap-3 rounded-[10px] bg-white p-4"
      style={{ boxShadow: t.cardShadow }}
      data-testid="rebaseline-card"
    >
      <div>
        <div className="text-[15px] font-medium" style={{ color: t.title, letterSpacing: '-0.24px' }}>
          Full re-baseline
        </div>
        <p className="mt-0.5 max-w-xl text-sm" style={{ color: t.textSecondary }}>
          {snapshotInProgress
            ? 'The client is re-sending its entire dataset. It runs to completion — the baseline is replaced when it commits.'
            : 'The client will re-send a full snapshot on next connect. Use when the changelog and checkpoints have diverged.'}
        </p>
      </div>
      {snapshotInProgress ? (
        <Pill label="Full snapshot in progress" tone="critical" />
      ) : rebaselineRequested ? (
        <div className="flex flex-wrap items-center gap-2">
          <Pill label="Full snapshot scheduled on next connect" tone="elevated" />
          {cancellable && (
            <button
              type="button"
              onClick={onCancel}
              disabled={cancelling}
              className="rounded-lg border bg-white px-3 py-1.5 text-sm font-medium transition-colors hover:bg-[#F5F5F4] disabled:cursor-not-allowed disabled:opacity-50"
              style={{ borderColor: t.border, color: t.text }}
            >
              Cancel request
            </button>
          )}
        </div>
      ) : (
        <button
          type="button"
          onClick={onRequest}
          className="rounded-lg border bg-white px-3 py-1.5 text-sm font-medium transition-colors hover:bg-[#FEF2F2]"
          style={{ borderColor: 'rgba(239,68,68,0.35)', color: '#B91C1C' }}
        >
          Request full re-baseline
        </button>
      )}
    </div>
  );
}

/**
 * RebaselineCard — the full re-baseline action card (023, F9).
 *
 * Design handoff v2 §1e: visible to owner AND admin. While the persistent
 * rebaselineRequested flag is set the button is replaced by the amber
 * "Full snapshot scheduled on next connect" pill; the server clears the flag
 * when the client starts its FULL_SNAPSHOT session and the button returns.
 *
 * The pill is paired with a cancel action (#84) so a mis-click can be taken
 * back while the client has not started the snapshot yet.
 */

import { monitoringTokens as t, severityTokens } from '@/shared/ui/tokens';

interface RebaselineCardProps {
  /** Persistent rebaseline_requested flag from the sync-state DTO. */
  rebaselineRequested: boolean;
  /** Opens the confirmation dialog. */
  onRequest: () => void;
  /** Opens the cancellation dialog (only reachable while a request is pending). */
  onCancel: () => void;
  /** True while the cancellation is in flight — holds the button against double submits. */
  cancelling?: boolean;
}

export function RebaselineCard({ rebaselineRequested, onRequest, onCancel, cancelling }: RebaselineCardProps) {
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
          The client will re-send a full snapshot on next connect. Use when the changelog and
          checkpoints have diverged.
        </p>
      </div>
      {rebaselineRequested ? (
        <div className="flex flex-wrap items-center gap-2">
          <span
            className="rounded-full px-3 py-1.5 text-xs font-medium"
            style={{ background: severityTokens.elevated.bg, color: severityTokens.elevated.text }}
          >
            Full snapshot scheduled on next connect
          </span>
          <button
            type="button"
            onClick={onCancel}
            disabled={cancelling}
            className="rounded-lg border bg-white px-3 py-1.5 text-sm font-medium transition-colors hover:bg-[#F5F5F4] disabled:cursor-not-allowed disabled:opacity-50"
            style={{ borderColor: t.border, color: t.text }}
          >
            Cancel request
          </button>
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

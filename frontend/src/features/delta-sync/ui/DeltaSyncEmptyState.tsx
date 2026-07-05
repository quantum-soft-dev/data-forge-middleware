/**
 * DeltaSyncEmptyState — the whole Delta Sync tab body when the site has no
 * sync-state row yet (client never connected). Design handoff v2 §1a "Empty
 * state" / decision D9a: one centered card, nothing else renders.
 */

import { Database } from 'lucide-react';
import { monitoringTokens as t } from '../model/tokens';

export function DeltaSyncEmptyState() {
  return (
    <div
      className="flex flex-col items-center justify-center rounded-[10px] bg-white px-6 py-16 text-center"
      style={{ boxShadow: t.cardShadow }}
      data-testid="delta-sync-empty-state"
    >
      <div
        className="flex h-11 w-11 items-center justify-center rounded-full"
        style={{ background: t.blue50 }}
      >
        <Database className="h-5 w-5" style={{ color: t.primary }} strokeWidth={1.5} />
      </div>
      <div className="mt-3 text-[15px] font-medium" style={{ color: t.title }}>
        No sync activity yet
      </div>
      <p className="mt-1 max-w-md text-sm" style={{ color: t.textSecondary }}>
        The Delta client for this site has not connected yet. Sync state, checkpoints and
        activity will appear after the first session.
      </p>
    </div>
  );
}

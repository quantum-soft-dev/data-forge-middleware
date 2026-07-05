/**
 * DeltaSyncWidget — the Delta Sync tab of the site-detail page (023, F5).
 *
 * Owner and admin render the same widget; `canManage` gates the operational
 * surfaces (rebuild button, segments card — F7/F8). Polls sync-state via
 * TanStack Query; the empty state replaces the entire tab body (D9a).
 */

import { useDeltaSyncState } from '@/features/delta-sync/api/queries';
import { SyncStateShell } from '@/features/delta-sync/ui/SyncStateShell';
import { DeltaSyncEmptyState } from '@/features/delta-sync/ui/DeltaSyncEmptyState';

export interface DeltaSyncWidgetProps {
  siteId: string;
  /** True when rendered from the admin entry — uses the /v1/sites API namespace. */
  admin: boolean;
  /** Admin capability flag: rebuild button + segments card. */
  canManage: boolean;
}

export function DeltaSyncWidget({ siteId, admin, canManage }: DeltaSyncWidgetProps) {
  const syncStateQuery = useDeltaSyncState(siteId, { admin });

  if (syncStateQuery.isLoading) {
    return (
      <div className="space-y-4" data-testid="delta-sync-loading">
        <div className="h-56 animate-pulse rounded-2xl bg-gray-100" />
        <div className="h-40 animate-pulse rounded-[10px] bg-gray-100" />
      </div>
    );
  }

  if (syncStateQuery.isError) {
    return (
      <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-800">
        Failed to load sync state. Please try again.
      </div>
    );
  }

  const state = syncStateQuery.data;
  if (!state) {
    return <DeltaSyncEmptyState />;
  }

  return (
    <div className="space-y-4" data-can-manage={canManage}>
      <SyncStateShell state={state} />
    </div>
  );
}

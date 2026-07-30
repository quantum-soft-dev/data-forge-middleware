/**
 * DeltaSyncWidget — the Delta Sync tab of the site-detail page (023, F5).
 *
 * Owner and admin render the same widget; `canManage` gates the operational
 * surfaces (rebuild button, segments card — F7/F8). Polls sync-state via
 * TanStack Query; the empty state replaces the entire tab body (D9a).
 */

import { useState } from 'react';
import { toast } from 'sonner';
import {
  useCancelRebaseline,
  useDeltaCheckpoints,
  useDeltaSegments,
  useDeltaSyncState,
  useRebuildCheckpoint,
  useRequestRebaseline,
} from '@/features/delta-sync/api/queries';
import { presignCheckpointDownload } from '@/features/delta-sync/api/deltaSyncApi';
import { openPresignedDownload } from '@/features/delta-sync/lib/downloadPresigned';
import type { DeltaCheckpointFormat } from '@/features/delta-sync/model/types';
import { CheckpointsCard } from '@/features/delta-sync/ui/CheckpointsCard';
import { RecentSegmentsCard } from '@/features/delta-sync/ui/RecentSegmentsCard';
import { RebaselineCard } from '@/features/delta-sync/ui/RebaselineCard';
import {
  CancelRebaselineDialog,
  RebaselineDialog,
  RebuildCheckpointDialog,
} from '@/features/delta-sync/ui/DeltaSyncDialogs';
import { computeLag, getSyncSeverity } from '@/features/delta-sync/model/severity';
import { useLagHistory } from '@/features/delta-sync/model/useLagHistory';
import { ActivityCard } from '@/features/delta-sync/ui/ActivityCard';
import { SyncStateShell } from '@/features/delta-sync/ui/SyncStateShell';
import { DeltaSyncEmptyState } from '@/features/delta-sync/ui/DeltaSyncEmptyState';

export interface DeltaSyncWidgetProps {
  siteId: string;
  /** True when rendered from the admin entry — uses the /v1/sites API namespace. */
  admin: boolean;
  /** Admin capability flag: rebuild button + segments card. */
  canManage: boolean;
}

function QueryErrorPanel({ resource }: { resource: string }) {
  return (
    <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-800">
      Failed to load {resource}. Please try again.
    </div>
  );
}

export function DeltaSyncWidget({ siteId, admin, canManage }: DeltaSyncWidgetProps) {
  const syncStateQuery = useDeltaSyncState(siteId, { admin });
  const checkpointsQuery = useDeltaCheckpoints(siteId, { admin });
  // Throughput bars are admin-only while P2 (owner lite segments) is pending.
  const segmentsQuery = useDeltaSegments(siteId, { enabled: canManage, limit: 20 });

  const [rebuildDialogOpen, setRebuildDialogOpen] = useState(false);
  const [rebaselineDialogOpen, setRebaselineDialogOpen] = useState(false);
  const [cancelRebaselineDialogOpen, setCancelRebaselineDialogOpen] = useState(false);

  const rebuildMutation = useRebuildCheckpoint(siteId);
  const rebaselineMutation = useRequestRebaseline(siteId, { admin });
  const cancelRebaselineMutation = useCancelRebaseline(siteId, { admin });

  const handleRebuildConfirm = () => {
    setRebuildDialogOpen(false);
    rebuildMutation.mutate(undefined, {
      onSuccess: () => toast.success('Checkpoint rebuild scheduled'),
      onError: () => toast.error('Something went wrong. Please try again.'),
    });
  };

  const handleRebaselineConfirm = () => {
    setRebaselineDialogOpen(false);
    rebaselineMutation.mutate(undefined, {
      onSuccess: () => toast.success('Full re-baseline requested'),
      onError: () => toast.error('Something went wrong. Please try again.'),
    });
  };

  // The pill can be up to one poll (20s) stale, so the request may already be gone by the time
  // the user confirms — `not-requested` means the client started the snapshot and nothing was
  // called off. Reporting that as a success would tell the user the re-upload was averted (#84).
  const handleCancelRebaselineConfirm = () => {
    setCancelRebaselineDialogOpen(false);
    cancelRebaselineMutation.mutate(undefined, {
      onSuccess: (status) =>
        status === 'cancelled'
          ? toast.success('Re-baseline request cancelled')
          : toast.warning('The client already started the full snapshot — it can no longer be cancelled'),
      onError: () => toast.error('Something went wrong. Please try again.'),
    });
  };

  // Every click mints a fresh presigned URL (15-min TTL) — never cached (§9).
  const handleDownload = (tableName: string, format: DeltaCheckpointFormat) =>
    openPresignedDownload(() => presignCheckpointDownload(siteId, tableName, format, { admin }), {
      notFoundMessage: `No ${format.toUpperCase()} checkpoint file for "${tableName}" — it may not have been built yet.`,
    });

  const lag = syncStateQuery.data ? computeLag(syncStateQuery.data) : null;
  const lagSamples = useLagHistory(lag, syncStateQuery.dataUpdatedAt ?? 0);

  if (syncStateQuery.isLoading) {
    return (
      <div className="space-y-4" data-testid="delta-sync-loading">
        <div className="h-56 animate-pulse rounded-2xl bg-surface-subtle" />
        <div className="h-40 animate-pulse rounded-[10px] bg-surface-subtle" />
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

  const severity = getSyncSeverity(computeLag(state), state.updatedAt);

  return (
    <div className="space-y-4" data-can-manage={canManage}>
      <SyncStateShell state={state} />
      <ActivityCard
        lagSamples={lagSamples}
        severity={severity}
        segments={segmentsQuery.data}
        showThroughput={canManage && !segmentsQuery.isError}
      />
      {/* A failed query must read as a failure, not as a legitimate empty list. */}
      {checkpointsQuery.isError ? (
        <QueryErrorPanel resource="checkpoints" />
      ) : (
        <CheckpointsCard
          checkpoints={checkpointsQuery.data ?? []}
          canManage={canManage}
          onDownload={handleDownload}
          onRebuild={() => setRebuildDialogOpen(true)}
          rebuildQueued={state.rebuildRequested || rebuildMutation.isPending}
        />
      )}
      {canManage &&
        (segmentsQuery.isError ? (
          <QueryErrorPanel resource="segments" />
        ) : (
          <RecentSegmentsCard segments={segmentsQuery.data ?? []} isLoading={segmentsQuery.isLoading} />
        ))}
      <RebaselineCard
        rebaselineRequested={state.rebaselineRequested || rebaselineMutation.isPending}
        onRequest={() => setRebaselineDialogOpen(true)}
        onCancel={() => setCancelRebaselineDialogOpen(true)}
        cancelling={cancelRebaselineMutation.isPending}
      />
      <RebuildCheckpointDialog
        open={rebuildDialogOpen}
        onOpenChange={setRebuildDialogOpen}
        onConfirm={handleRebuildConfirm}
      />
      <RebaselineDialog
        open={rebaselineDialogOpen}
        onOpenChange={setRebaselineDialogOpen}
        onConfirm={handleRebaselineConfirm}
      />
      <CancelRebaselineDialog
        open={cancelRebaselineDialogOpen}
        onOpenChange={setCancelRebaselineDialogOpen}
        onConfirm={handleCancelRebaselineConfirm}
      />
    </div>
  );
}

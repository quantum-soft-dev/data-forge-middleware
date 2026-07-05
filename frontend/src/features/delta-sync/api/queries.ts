/**
 * Delta Sync queries & mutations (023, F4).
 *
 * Polling per design handoff: sync-state and checkpoints every 20–30 s via
 * refetchInterval; segments are fetched on card expand; both actions invalidate
 * sync-state + checkpoints on success.
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getDeltaCheckpoints,
  getDeltaSegments,
  getDeltaSyncHealth,
  getDeltaSyncState,
  requestCheckpointRebuild,
  requestRebaseline,
  type DeltaApiScope,
} from './deltaSyncApi';

export const SYNC_STATE_POLL_MS = 20_000;
export const CHECKPOINTS_POLL_MS = 30_000;
export const HEALTH_POLL_MS = 30_000;

export const deltaSyncKeys = {
  all: ['delta-sync'] as const,
  syncState: (siteId: string) => [...deltaSyncKeys.all, 'sync-state', siteId] as const,
  checkpoints: (siteId: string) => [...deltaSyncKeys.all, 'checkpoints', siteId] as const,
  segments: (siteId: string, limit: number) => [...deltaSyncKeys.all, 'segments', siteId, { limit }] as const,
  health: (accountId?: string) => [...deltaSyncKeys.all, 'health', accountId ?? 'user'] as const,
};

export function useDeltaSyncState(siteId: string, scope: DeltaApiScope, options?: { enabled?: boolean }) {
  return useQuery({
    queryKey: deltaSyncKeys.syncState(siteId),
    queryFn: () => getDeltaSyncState(siteId, scope),
    enabled: options?.enabled ?? true,
    refetchInterval: SYNC_STATE_POLL_MS,
    staleTime: 0,
  });
}

export function useDeltaCheckpoints(siteId: string, scope: DeltaApiScope, options?: { enabled?: boolean }) {
  return useQuery({
    queryKey: deltaSyncKeys.checkpoints(siteId),
    queryFn: () => getDeltaCheckpoints(siteId, scope),
    enabled: options?.enabled ?? true,
    refetchInterval: CHECKPOINTS_POLL_MS,
    staleTime: 0,
  });
}

/** Admin only; fetched lazily when the Recent segments card expands. */
export function useDeltaSegments(siteId: string, options?: { enabled?: boolean; limit?: number }) {
  const limit = options?.limit ?? 20;
  return useQuery({
    queryKey: deltaSyncKeys.segments(siteId, limit),
    queryFn: () => getDeltaSegments(siteId, limit),
    enabled: options?.enabled ?? false,
  });
}

export function useRebuildCheckpoint(siteId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => requestCheckpointRebuild(siteId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: deltaSyncKeys.syncState(siteId) });
      queryClient.invalidateQueries({ queryKey: deltaSyncKeys.checkpoints(siteId) });
    },
  });
}

export function useRequestRebaseline(siteId: string, scope: DeltaApiScope) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => requestRebaseline(siteId, scope),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: deltaSyncKeys.syncState(siteId) });
      queryClient.invalidateQueries({ queryKey: deltaSyncKeys.checkpoints(siteId) });
    },
  });
}

/** Bulk site-list health poll; renders nothing while loading (design D5). */
export function useDeltaSyncHealth(options?: { accountId?: string; enabled?: boolean }) {
  return useQuery({
    queryKey: deltaSyncKeys.health(options?.accountId),
    queryFn: () => getDeltaSyncHealth(options?.accountId),
    enabled: options?.enabled ?? true,
    refetchInterval: HEALTH_POLL_MS,
    staleTime: 0,
  });
}

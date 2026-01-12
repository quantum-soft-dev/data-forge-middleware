/**
 * TanStack Query hooks for device authorization.
 *
 * Provides React hooks for device authorization flow.
 *
 * @see docs/016-device-authorization-grant.md
 * @version 1.0.0
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getDeviceCodeInfo,
  confirmDeviceAuthorization,
  denyDeviceAuthorization,
} from '../api/deviceAuthApi';
import type { ConfirmDeviceRequest } from './types';

/**
 * Query keys factory for cache management.
 */
export const deviceAuthKeys = {
  all: ['deviceAuth'] as const,
  codeInfo: (userCode: string) => [...deviceAuthKeys.all, 'codeInfo', userCode] as const,
};

/**
 * Fetch device code information for a given user code.
 *
 * @param userCode - The user code to look up
 * @param options - Query options
 * @returns Query result with device code info
 */
export function useDeviceCodeInfo(userCode: string, options?: { enabled?: boolean }) {
  return useQuery({
    queryKey: deviceAuthKeys.codeInfo(userCode),
    queryFn: () => getDeviceCodeInfo(userCode),
    enabled: (options?.enabled ?? true) && userCode.length > 0,
    retry: false, // Don't retry on 404
    staleTime: 30000, // 30 seconds
    gcTime: 60000, // 1 minute
  });
}

/**
 * Confirm device authorization mutation.
 *
 * @returns Mutation for confirming device authorization
 */
export function useConfirmDevice() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (request: ConfirmDeviceRequest) => confirmDeviceAuthorization(request),
    onSuccess: (_data, variables) => {
      // Invalidate device code query to update status
      queryClient.invalidateQueries({ queryKey: deviceAuthKeys.codeInfo(variables.userCode) });
    },
  });
}

/**
 * Deny device authorization mutation.
 *
 * @returns Mutation for denying device authorization
 */
export function useDenyDevice() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (userCode: string) => denyDeviceAuthorization(userCode),
    onSuccess: (_data, userCode) => {
      // Invalidate device code query to update status
      queryClient.invalidateQueries({ queryKey: deviceAuthKeys.codeInfo(userCode) });
    },
  });
}

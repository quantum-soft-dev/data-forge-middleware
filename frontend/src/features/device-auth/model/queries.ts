/**
 * TanStack Query hooks for device authorization.
 *
 * Provides React hooks for device authorization verification flow.
 *
 * @see docs/client-integration.md
 * @version 2.0.0
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getVerifyInfo,
  approveAuthorization,
  denyAuthorization,
} from '../api/deviceAuthApi';

/**
 * Query keys factory for cache management.
 */
export const deviceAuthKeys = {
  all: ['deviceAuth'] as const,
  verifyInfo: (userCode: string) => [...deviceAuthKeys.all, 'verifyInfo', userCode] as const,
};

/**
 * Fetch authorization info for a given user code.
 *
 * @param userCode - The user code to look up (e.g., "ABCD-1234")
 * @param options - Query options
 * @returns Query result with authorization info (siteName, siteDescription, expiresAt)
 */
export function useVerifyInfo(userCode: string, options?: { enabled?: boolean }) {
  return useQuery({
    queryKey: deviceAuthKeys.verifyInfo(userCode),
    queryFn: () => getVerifyInfo(userCode),
    enabled: (options?.enabled ?? true) && userCode.length >= 8,
    retry: false, // Don't retry on 404
    staleTime: 30000, // 30 seconds
    gcTime: 60000, // 1 minute
  });
}

/**
 * Approve device authorization mutation.
 * Creates site automatically on success.
 *
 * @returns Mutation for approving device authorization
 */
export function useApproveAuthorization() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (userCode: string) => approveAuthorization(userCode),
    onSuccess: (_data, userCode) => {
      // Remove verification info from cache (don't refetch - code is already used)
      queryClient.removeQueries({ queryKey: deviceAuthKeys.verifyInfo(userCode) });
    },
  });
}

/**
 * Deny device authorization mutation.
 *
 * @returns Mutation for denying device authorization
 */
export function useDenyAuthorization() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (userCode: string) => denyAuthorization(userCode),
    onSuccess: (_data, userCode) => {
      // Remove verification info from cache (don't refetch - code is already used)
      queryClient.removeQueries({ queryKey: deviceAuthKeys.verifyInfo(userCode) });
    },
  });
}

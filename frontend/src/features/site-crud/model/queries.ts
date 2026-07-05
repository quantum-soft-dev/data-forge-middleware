/**
 * TanStack Query hooks for site management.
 *
 * Provides type-safe React hooks for site CRUD operations with automatic caching,
 * refetching, and optimistic updates.
 *
 * Feature: 007-adding-a-site (T024)
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  listUserSites,
  createUserSite,
  deactivateUserSite,
  activateUserSite,
  deleteUserSite,
  listAdminSites,
  getAdminSite,
  createAdminSite,
  deactivateAdminSite,
  activateAdminSite,
  deleteAdminSite,
  updateAdminSiteRetention,
  type Site,
  type CreateSiteRequest,
} from '@/entities/site';

/**
 * Query keys factory for cache management.
 */
export const siteKeys = {
  all: ['sites'] as const,
  lists: () => [...siteKeys.all, 'list'] as const,
  list: (accountId?: string) =>
    accountId ? [...siteKeys.lists(), accountId] : [...siteKeys.lists(), 'user'] as const,
  detail: (siteId: string) => [...siteKeys.all, 'detail', siteId] as const,
};

/**
 * USER HOOKS
 */

/**
 * Fetch all sites (both active and inactive) for the authenticated user.
 *
 * @param options - Query options
 * @returns Query result with sites list
 */
export function useSites(options?: { enabled?: boolean }) {
  return useQuery({
    queryKey: siteKeys.list(),
    queryFn: listUserSites,
    enabled: options?.enabled ?? true,
    staleTime: 30000, // 30 seconds
    gcTime: 300000, // 5 minutes
  });
}

/**
 * Create a new site for the authenticated user.
 *
 * @returns Mutation for creating a site
 */
export function useCreateSite() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: createUserSite,
    onSuccess: () => {
      // Invalidate sites list to refetch
      queryClient.invalidateQueries({ queryKey: siteKeys.list() });
    },
  });
}

/**
 * Update site status (activate/deactivate).
 *
 * @returns Mutation for updating site status
 */
export function useUpdateSiteStatus() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({
      siteId,
      isActive,
    }: {
      siteId: string;
      isActive: boolean;
    }): Promise<Site> => {
      return isActive ? await activateUserSite(siteId) : await deactivateUserSite(siteId);
    },
    onMutate: async ({ siteId, isActive }) => {
      // Cancel outgoing refetches
      await queryClient.cancelQueries({ queryKey: siteKeys.list() });

      // Snapshot previous value
      const previousSites = queryClient.getQueryData<Site[]>(siteKeys.list());

      // Optimistically update
      if (previousSites) {
        queryClient.setQueryData<Site[]>(
          siteKeys.list(),
          previousSites.map((site) =>
            site.id === siteId ? { ...site, isActive } : site
          )
        );
      }

      return { previousSites };
    },
    onError: (_error, _variables, context) => {
      // Rollback on error
      if (context?.previousSites) {
        queryClient.setQueryData(siteKeys.list(), context.previousSites);
      }
    },
    onSettled: () => {
      // Refetch after mutation
      queryClient.invalidateQueries({ queryKey: siteKeys.list() });
    },
  });
}

/**
 * Delete a site (soft delete).
 *
 * @returns Mutation for deleting a site
 */
export function useDeleteSite() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: deleteUserSite,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: siteKeys.list() });
    },
  });
}

/**
 * ADMIN HOOKS
 */

/**
 * Fetch all sites (both active and inactive) for a specific account (admin view).
 *
 * @param accountId - Account identifier
 * @returns Query result with sites list
 */
export function useAdminSites(accountId: string) {
  return useQuery({
    queryKey: siteKeys.list(accountId),
    queryFn: () => listAdminSites(accountId),
    enabled: !!accountId,
    staleTime: 30000,
    gcTime: 300000,
  });
}

/**
 * Fetch a single site by id (admin view) — 023, F3 admin site-detail page.
 */
export function useAdminSite(siteId: string, options?: { enabled?: boolean }) {
  return useQuery({
    queryKey: siteKeys.detail(siteId),
    queryFn: () => getAdminSite(siteId),
    enabled: (options?.enabled ?? true) && !!siteId,
  });
}

/**
 * Create a new site for a user (admin operation).
 *
 * @param accountId - Account identifier
 * @returns Mutation for creating a site
 */
export function useCreateAdminSite(accountId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (request: CreateSiteRequest) => createAdminSite(accountId, request),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: siteKeys.list(accountId) });
    },
  });
}

/**
 * Update site status for a user (admin operation).
 *
 * @param accountId - Account identifier
 * @returns Mutation for updating site status
 */
export function useAdminUpdateSiteStatus(accountId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({
      siteId,
      isActive,
    }: {
      siteId: string;
      isActive: boolean;
    }): Promise<Site> => {
      return isActive
        ? await activateAdminSite(accountId, siteId)
        : await deactivateAdminSite(accountId, siteId);
    },
    onMutate: async ({ siteId, isActive }) => {
      await queryClient.cancelQueries({ queryKey: siteKeys.list(accountId) });

      const previousSites = queryClient.getQueryData<Site[]>(siteKeys.list(accountId));

      if (previousSites) {
        queryClient.setQueryData<Site[]>(
          siteKeys.list(accountId),
          previousSites.map((site) =>
            site.id === siteId ? { ...site, isActive } : site
          )
        );
      }

      return { previousSites };
    },
    onError: (_error, _variables, context) => {
      if (context?.previousSites) {
        queryClient.setQueryData(siteKeys.list(accountId), context.previousSites);
      }
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: siteKeys.list(accountId) });
    },
  });
}

/**
 * Delete a user's site (admin operation).
 *
 * @param accountId - Account identifier
 * @returns Mutation for deleting a site
 */
export function useAdminDeleteSite(accountId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (siteId: string) => deleteAdminSite(accountId, siteId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: siteKeys.list(accountId) });
    },
  });
}

/**
 * Update site retention policy (admin operation).
 *
 * @param accountId - Account identifier
 * @returns Mutation for updating retention days
 */
export function useAdminUpdateSiteRetention(accountId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({
      siteId,
      retentionDays,
    }: {
      siteId: string;
      retentionDays: number;
    }): Promise<Site> => {
      return updateAdminSiteRetention(siteId, retentionDays);
    },
    onMutate: async ({ siteId, retentionDays }) => {
      await queryClient.cancelQueries({ queryKey: siteKeys.list(accountId) });

      const previousSites = queryClient.getQueryData<Site[]>(siteKeys.list(accountId));

      if (previousSites) {
        queryClient.setQueryData<Site[]>(
          siteKeys.list(accountId),
          previousSites.map((site) =>
            site.id === siteId ? { ...site, retentionDays } : site
          )
        );
      }

      return { previousSites };
    },
    onError: (_error, _variables, context) => {
      if (context?.previousSites) {
        queryClient.setQueryData(siteKeys.list(accountId), context.previousSites);
      }
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: siteKeys.list(accountId) });
    },
  });
}

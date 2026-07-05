/**
 * SiteList - Widget for displaying list of sites with actions.
 *
 * Features:
 * - Display sites sorted by creation date
 * - Activate/Deactivate site actions
 * - Delete site actions
 * - Loading and empty states
 * - Optimistic updates
 *
 * Feature: 007-adding-a-site (T035, US1, US2, US3)
 */

import { Alert, AlertDescription } from '@/shared/ui/ui/alert';
import { Skeleton } from '@/shared/ui/ui/skeleton';
import { SiteListItem } from './ui/SiteListItem';
import {
  useSites,
  useAdminSites,
  useUpdateSiteStatus,
  useAdminUpdateSiteStatus,
  useAdminUpdateSiteRetention,
  useDeleteSite,
  useAdminDeleteSite
} from '@/features/site-crud/model/queries';
import { AlertCircle } from 'lucide-react';
import { toast } from 'sonner';
import { useNavigate } from '@tanstack/react-router';
import { useDeltaSyncHealth } from '@/features/delta-sync/api/queries';
import { SyncHealthPill } from '@/features/delta-sync/ui/SyncHealthPill';

interface SiteListProps {
  /**
   * Account ID for admin context.
   * If provided, shows sites for specified account (admin view).
   * If not provided, shows sites for authenticated user (user view).
   */
  accountId?: string;

  /**
   * Whether to show the list in a compact layout.
   * @default false
   */
  compact?: boolean;
}

export function SiteList({ accountId, compact = false }: SiteListProps) {
  const navigate = useNavigate();
  // Use admin or user queries based on context
  // When accountId is provided (admin context), only admin query runs
  // When accountId is not provided (user context), only user query runs
  const isAdminContext = !!accountId;

  // Conditionally enable queries based on context
  const adminSitesQuery = useAdminSites(accountId || ''); // enabled by accountId
  const userSitesQuery = useSites({ enabled: !isAdminContext }); // disabled when admin context

  const { data: sites, isLoading, error } = isAdminContext ? adminSitesQuery : userSitesQuery;

  // Bulk sync health for all V2 sites of the account — one request per 30s poll (B10/F11).
  const healthQuery = useDeltaSyncHealth({ accountId });
  const healthBySiteId = new Map((healthQuery.data ?? []).map((entry) => [entry.siteId, entry]));

  // Use admin or user mutations based on context
  const userUpdateMutation = useUpdateSiteStatus();
  const adminUpdateMutation = accountId ? useAdminUpdateSiteStatus(accountId) : null;
  const updateStatusMutation = adminUpdateMutation || userUpdateMutation;

  const userDeleteMutation = useDeleteSite();
  const adminDeleteMutation = accountId ? useAdminDeleteSite(accountId) : null;
  const deleteSiteMutation = adminDeleteMutation || userDeleteMutation;

  const adminRetentionMutation = accountId ? useAdminUpdateSiteRetention(accountId) : null;

  const handleActivate = async (siteId: string) => {
    try {
      await updateStatusMutation.mutateAsync({ siteId, isActive: true });
      toast.success('Site activated successfully');
    } catch (error: any) {
      toast.error(error?.response?.data?.message || 'Failed to activate site');
    }
  };

  const handleDeactivate = async (siteId: string) => {
    try {
      await updateStatusMutation.mutateAsync({ siteId, isActive: false });
      toast.success('Site deactivated successfully');
    } catch (error: any) {
      toast.error(error?.response?.data?.message || 'Failed to deactivate site');
    }
  };

  const handleDelete = async (siteId: string) => {
    try {
      await deleteSiteMutation.mutateAsync(siteId);
      toast.success('Site deleted successfully');
    } catch (error: any) {
      toast.error(error?.response?.data?.message || 'Failed to delete site');
    }
  };

  // 023, F3: a row click opens the site-detail shell (owner or admin entry)
  const handleOpen = (siteId: string) => {
    if (accountId) {
      navigate({ to: '/admin/sites/$siteId', params: { siteId } });
    } else {
      navigate({ to: '/account/sites/$siteId', params: { siteId } });
    }
  };

  const handleRetentionUpdate = async (siteId: string, retentionDays: number) => {
    if (!adminRetentionMutation) return;
    try {
      await adminRetentionMutation.mutateAsync({ siteId, retentionDays });
      toast.success('Retention policy updated');
    } catch (error: any) {
      toast.error(error?.response?.data?.message || 'Failed to update retention policy');
    }
  };

  // Loading state
  if (isLoading) {
    return (
      <div className="space-y-3">
        {[1, 2, 3].map((i) => (
          <Skeleton key={i} className="h-24 w-full" />
        ))}
      </div>
    );
  }

  // Error state
  if (error) {
    return (
      <Alert variant="destructive">
        <AlertCircle className="h-4 w-4" />
        <AlertDescription>
          Failed to load sites. Please try again later.
          <br />
          <span className="text-xs mt-1 block">
            {error instanceof Error ? error.message : 'Unknown error'}
          </span>
        </AlertDescription>
      </Alert>
    );
  }

  // Empty state
  if (!sites || sites.length === 0) {
    return (
      <Alert>
        <AlertDescription>
          No sites found. Create your first site to get started.
        </AlertDescription>
      </Alert>
    );
  }

  // Sites list
  return (
    <div className={compact ? 'space-y-2' : 'space-y-3'}>
      {sites.map((site) => (
        <SiteListItem
          key={site.id}
          site={site}
          statusSlot={
            <SyncHealthPill
              clientApiVersion={site.clientApiVersion}
              health={healthBySiteId.get(site.id)}
              isLoading={healthQuery.isLoading}
            />
          }
          onActivate={handleActivate}
          onDeactivate={handleDeactivate}
          onOpen={handleOpen}
          onDelete={handleDelete}
          onUpdateRetention={accountId ? handleRetentionUpdate : undefined}
          showRetentionControls={!!accountId}
          isLoading={
            updateStatusMutation.isPending ||
            deleteSiteMutation.isPending ||
            (adminRetentionMutation?.isPending ?? false)
          }
        />
      ))}
    </div>
  );
}

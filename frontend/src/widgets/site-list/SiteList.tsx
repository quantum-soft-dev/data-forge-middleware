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

import { Alert, AlertDescription } from '@/shared/ui/components/alert';
import { Skeleton } from '@/shared/ui/components/skeleton';
import { SiteListItem } from './ui/SiteListItem';
import { useSites, useUpdateSiteStatus, useDeleteSite } from '@/features/site-crud/model/queries';
import { AlertCircle } from 'lucide-react';
import { toast } from 'sonner';

interface SiteListProps {
  /**
   * Whether to show the list in a compact layout.
   * @default false
   */
  compact?: boolean;
}

export function SiteList({ compact = false }: SiteListProps) {
  const { data: sites, isLoading, error } = useSites();
  const updateStatusMutation = useUpdateSiteStatus();
  const deleteSiteMutation = useDeleteSite();

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
          onActivate={handleActivate}
          onDeactivate={handleDeactivate}
          onDelete={handleDelete}
          isLoading={updateStatusMutation.isPending || deleteSiteMutation.isPending}
        />
      ))}
    </div>
  );
}

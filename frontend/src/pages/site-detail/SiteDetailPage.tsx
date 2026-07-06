/**
 * SiteDetailPage - data wiring for the site-detail shell (023, F3).
 *
 * Two entries share this page (design D4): the owner dashboard
 * (/account/sites/$siteId, sites resolved from the user's site list) and the
 * admin panel (/admin/sites/$siteId, site fetched by id via the admin API).
 * Only `canManage` differs downstream.
 */

import { useNavigate, useParams } from '@tanstack/react-router';
import { Loader2 } from 'lucide-react';
import { Header } from '@/widgets/header/Header';
import { useAuth } from '@/entities/user-session/api/useAuth';
import { useAdminSite, useSites } from '@/features/site-crud/model/queries';
import { SiteDetailShell } from './SiteDetailShell';

interface SiteDetailPageProps {
  /** True when mounted under the admin route (/admin/sites/$siteId). */
  admin?: boolean;
}

export function SiteDetailPage({ admin = false }: SiteDetailPageProps) {
  const navigate = useNavigate();
  const params = useParams({ strict: false }) as { siteId: string };
  const siteId = params.siteId;

  const { hasRole, isRolesLoading } = useAuth();
  const canManage = !isRolesLoading && hasRole('ROLE_ADMIN');

  const userSitesQuery = useSites({ enabled: !admin });
  const adminSiteQuery = useAdminSite(siteId, { enabled: admin });

  const site = admin
    ? adminSiteQuery.data
    : userSitesQuery.data?.find((s) => s.id === siteId);
  const isLoading = admin ? adminSiteQuery.isLoading : userSitesQuery.isLoading;
  const error = admin ? adminSiteQuery.error : userSitesQuery.error;

  const handleBack = () => {
    if (admin && site) {
      navigate({ to: '/admin/users/$id/sites', params: { id: site.accountId } });
    } else if (admin) {
      navigate({ to: '/admin/users' });
    } else {
      navigate({ to: '/account/sites' });
    }
  };

  return (
    <div className="min-h-screen bg-surface-hover">
      <Header />
      <main className="container mx-auto max-w-[1120px] px-6 py-6">
        {isLoading && (
          <div className="flex items-center justify-center py-16">
            <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
          </div>
        )}
        {!isLoading && (error || !site) && (
          <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-800">
            {error ? 'Failed to load the site. Please try again.' : 'Site not found.'}
          </div>
        )}
        {!isLoading && site && (
          <SiteDetailShell site={site} canManage={canManage} admin={admin} onBack={handleBack} />
        )}
      </main>
    </div>
  );
}

export default SiteDetailPage;

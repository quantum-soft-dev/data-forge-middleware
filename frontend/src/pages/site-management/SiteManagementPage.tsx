/**
 * SiteManagementPage - User site management page.
 *
 * Features:
 * - Create new sites
 * - View all user's active sites
 * - Activate/Deactivate sites
 * - Delete sites
 *
 * Route: /account/sites
 *
 * Feature: 007-adding-a-site (T037, US1)
 */

import { CreateSiteForm } from '@/features/site-crud/ui/CreateSiteForm';
import { SiteList } from '@/widgets/site-list/SiteList';
import { Separator } from '@/shared/ui/components/separator';

export function SiteManagementPage() {
  return (
    <div className="container mx-auto py-6 space-y-6 max-w-4xl">
      {/* Page header */}
      <div>
        <h1 className="text-3xl font-bold tracking-tight">Site Management</h1>
        <p className="text-muted-foreground mt-2">
          Manage your sites and monitor their activity
        </p>
      </div>

      <Separator />

      {/* Create site form */}
      <section>
        <CreateSiteForm />
      </section>

      <Separator />

      {/* Sites list */}
      <section>
        <div className="mb-4">
          <h2 className="text-2xl font-semibold tracking-tight">Your Sites</h2>
          <p className="text-sm text-muted-foreground mt-1">
            All your active sites sorted by creation date
          </p>
        </div>
        <SiteList />
      </section>
    </div>
  );
}

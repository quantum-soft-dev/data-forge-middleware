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
import { Separator } from '@/shared/ui/ui/separator';
import { Header } from '@/widgets/header/Header';
import { PageHeader } from '@/shared/ui/page-header';

export function SiteManagementPage() {
  return (
    <div className="min-h-screen bg-white">
      <Header />

      <main className="container mx-auto max-w-[1120px] space-y-6 px-6 py-6">
        <PageHeader
          title="Site Management"
          subtitle="Manage your sites and monitor their activity"
        />

        <Separator />

        {/* Create site form */}
        <section>
          <CreateSiteForm />
        </section>

        <Separator />

        {/* Sites list */}
        <section>
          <div className="mb-4">
            <h2 className="text-[15px] font-medium tracking-[-0.24px] text-ink-title">
              Your Sites
            </h2>
            <p className="mt-1 text-sm text-ink-secondary">
              All your active sites sorted by creation date
            </p>
          </div>
          <SiteList />
        </section>
      </main>
    </div>
  );
}

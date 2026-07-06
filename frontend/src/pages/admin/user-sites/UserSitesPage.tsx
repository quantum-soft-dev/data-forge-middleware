/**
 * UserSitesPage - Admin view of user's sites
 *
 * Feature: 007-adding-a-site (US4, T085)
 *
 * Features:
 * - View all sites for a specific user
 * - Create new sites for the user
 * - Activate/Deactivate/Delete sites
 * - Audit logging for all actions
 *
 * Route: /admin/users/$id/sites
 * Requires: ROLE_ADMIN
 */

import { useParams, useNavigate } from '@tanstack/react-router'
import { ArrowLeft } from 'lucide-react'
import { Header } from '@/widgets/header/Header'
import { CreateSiteForm } from '@/features/site-crud/ui/CreateSiteForm'
import { SiteList } from '@/widgets/site-list/SiteList'
import { Separator } from '@/shared/ui/ui/separator'
import { useAccountQuery } from '@/features/user-management/api/userQueries'
import { BatchCleanupPanel } from '@/features/batch-cleanup/ui/BatchCleanupPanel'

export default function UserSitesPage() {
  const { id: accountId } = useParams({ from: '/admin/users/$id/sites' })
  const navigate = useNavigate()

  const { data: account, isLoading, isError, error } = useAccountQuery(accountId)

  const handleBack = () => {
    navigate({ to: '/admin/users/$id', params: { id: accountId } })
  }

  if (isLoading) {
    return (
      <div className="min-h-screen bg-white">
        <Header />
        <main className="mx-auto max-w-5xl px-4 py-8 sm:px-6 lg:px-8">
          <div className="flex items-center justify-center py-12">
            <div className="h-8 w-8 animate-spin rounded-full border-4 border-secondary border-t-brand" />
            <span className="ml-3 text-sm text-ink-secondary">Loading user sites...</span>
          </div>
        </main>
      </div>
    )
  }

  if (isError || !account) {
    return (
      <div className="min-h-screen bg-white">
        <Header />
        <main className="mx-auto max-w-5xl px-4 py-8 sm:px-6 lg:px-8">
          <div className="rounded-lg border border-danger-border bg-danger-bg p-4">
            <p className="text-sm font-medium text-danger-text">
              {error instanceof Error ? error.message : 'Failed to load user account'}
            </p>
          </div>
        </main>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-white">
      <Header />

      <main className="mx-auto max-w-5xl px-4 py-8 sm:px-6 lg:px-8">
        {/* Page header with back button */}
        <div className="mb-8">
          <button
            onClick={handleBack}
            className="mb-4 flex items-center gap-2 text-sm text-ink-secondary hover:text-ink"
          >
            <ArrowLeft className="h-4 w-4" />
            Back to Account Details
          </button>
          <div>
            <p className="text-sm text-ink-secondary">
              Manage sites for {account.name} ({account.email})
            </p>
          </div>
        </div>

        <Separator className="my-8" />

        {/* Create site form - admin context */}
        <section className="mb-8">
          <h2 className="text-[15px] font-medium tracking-[-0.24px] text-ink-title mb-4">Create New Site</h2>
          <CreateSiteForm accountId={accountId} />
        </section>

        <Separator className="my-8" />

        {/* Batch cleanup panel */}
        <section className="mb-8">
          <BatchCleanupPanel accountId={accountId} />
        </section>

        <Separator className="my-8" />

        {/* Sites list - admin context */}
        <section>
          <div className="mb-4">
            <h2 className="text-[15px] font-medium tracking-[-0.24px] text-ink-title">User's Sites</h2>
            <p className="text-sm text-ink-secondary mt-1">
              All sites for this user sorted by creation date
            </p>
          </div>
          <SiteList accountId={accountId} />
        </section>
      </main>
    </div>
  )
}

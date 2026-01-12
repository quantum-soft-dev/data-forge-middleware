/**
 * Dashboard Page
 *
 * Displays overview metrics, charts, and global errors widget.
 * Per FR-008: Shows demo charts with navigation and logout.
 *
 * Features:
 * - Navigation header with Dashboard/Accounts links
 * - 4 chart widgets in responsive grid
 * - Global errors widget with unread badge (regular users only)
 * - Logout button in header
 */

import { Header } from '@/widgets/header/Header'
import { DashboardCharts } from '@/widgets/dashboard-charts'
import { GlobalErrorsWidget } from '@/widgets/global-errors/GlobalErrorsWidget'
import { useAuth } from '@/entities/user-session/api/useAuth'

export default function DashboardPage() {
  const { hasRole, isRolesLoading } = useAuth()

  // Admin users don't have accountId claim, so hide the widget for them
  const isAdmin = hasRole('ROLE_ADMIN')
  const showGlobalErrors = !isRolesLoading && !isAdmin

  return (
    <div className="min-h-screen bg-gray-50">
      <Header />
      <main className="mx-auto max-w-7xl px-4 py-8 sm:px-6 lg:px-8">
        <div className="mb-8">
          <h1 className="text-3xl font-bold text-gray-900">Dashboard</h1>
          <p className="mt-2 text-sm text-gray-600">
            Overview of subscriber metrics and growth trends
          </p>
        </div>

        {/* Global Errors Widget - only shown for regular users with accountId */}
        {showGlobalErrors && (
          <div className="mb-8">
            <GlobalErrorsWidget pageSize={10} />
          </div>
        )}

        {/* Charts */}
        <DashboardCharts />
      </main>
    </div>
  )
}

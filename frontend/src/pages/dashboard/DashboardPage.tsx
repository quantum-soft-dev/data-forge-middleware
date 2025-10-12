/**
 * Dashboard Page
 *
 * Displays overview metrics and charts for subscriber data.
 * Per FR-008: Shows demo charts with navigation and logout.
 *
 * Features:
 * - Navigation header with Dashboard/Accounts links
 * - 4 chart widgets in responsive grid
 * - Logout button in header
 */

import { Header } from '@/widgets/header/Header'
import { DashboardCharts } from '@/widgets/dashboard-charts'

export default function DashboardPage() {
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
        <DashboardCharts />
      </main>
    </div>
  )
}

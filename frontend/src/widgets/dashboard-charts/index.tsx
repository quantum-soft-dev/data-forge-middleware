/**
 * Dashboard Charts Composition
 *
 * Composes all chart widgets into responsive grid layout.
 * Layout breakpoints:
 * - Mobile (<640px): Single column stack
 * - Tablet (640-1024px): 2 columns
 * - Desktop (≥1024px): 2x2 grid
 *
 * Per FR-008: Dashboard displays demo charts with demo data.
 */

import { generateDemoData } from '@/entities/dashboard-metrics/model/demo-data'
import { AreaChartWidget } from './AreaChartWidget'
import { PieChartWidget } from './PieChartWidget'
import { BarChartWidget } from './BarChartWidget'
import { TopCompaniesWidget } from './TopCompaniesWidget'

export function DashboardCharts() {
  const data = generateDemoData()

  return (
    <div
      data-testid="dashboard-charts-grid"
      className="grid gap-6 sm:grid-cols-1 md:grid-cols-2 lg:grid-cols-2"
    >
      <AreaChartWidget data={data.trendData} />
      <PieChartWidget data={data.statusDistribution} />
      <BarChartWidget data={data.monthlyGrowth} />
      <TopCompaniesWidget data={data.topCompanies} />
    </div>
  )
}

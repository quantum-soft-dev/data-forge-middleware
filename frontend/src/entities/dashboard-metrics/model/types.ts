/**
 * Dashboard Metrics Types
 *
 * Defines the structure for dashboard data visualization.
 * Used for demo data in Phase 4, will be replaced with real API data later.
 */

export interface TrendDataPoint {
  month: string
  subscribers: number
}

export interface StatusSegment {
  name: 'Active' | 'Inactive'
  value: number
}

export interface MonthlyGrowthPoint {
  month: string
  growth: number
}

export interface TopCompany {
  name: string
  subscribers: number
}

export interface DashboardMetrics {
  totalSubscribers: number
  trendData: TrendDataPoint[]
  statusDistribution: StatusSegment[]
  monthlyGrowth: MonthlyGrowthPoint[]
  topCompanies: TopCompany[]
}

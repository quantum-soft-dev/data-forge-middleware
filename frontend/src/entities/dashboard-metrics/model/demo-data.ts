/**
 * Demo Data Generator
 *
 * Generates consistent fake dashboard data for Phase 4.
 * Per spec.md FR-008: Dashboard displays demo charts until real API integration.
 *
 * Data characteristics:
 * - 450 total subscribers
 * - 12 months of historical data
 * - Active/Inactive distribution (89% active)
 * - Top 5 companies by subscriber count
 */

import type { DashboardMetrics } from './types'

const DEMO_TREND_DATA = [
  { month: 'Jan', subscribers: 180 },
  { month: 'Feb', subscribers: 220 },
  { month: 'Mar', subscribers: 250 },
  { month: 'Apr', subscribers: 280 },
  { month: 'May', subscribers: 310 },
  { month: 'Jun', subscribers: 340 },
  { month: 'Jul', subscribers: 370 },
  { month: 'Aug', subscribers: 390 },
  { month: 'Sep', subscribers: 410 },
  { month: 'Oct', subscribers: 430 },
  { month: 'Nov', subscribers: 440 },
  { month: 'Dec', subscribers: 450 },
]

const DEMO_MONTHLY_GROWTH = [
  { month: 'Jan', growth: 20 },
  { month: 'Feb', growth: 40 },
  { month: 'Mar', growth: 30 },
  { month: 'Apr', growth: 30 },
  { month: 'May', growth: 30 },
  { month: 'Jun', growth: 30 },
  { month: 'Jul', growth: 30 },
  { month: 'Aug', growth: 20 },
  { month: 'Sep', growth: 20 },
  { month: 'Oct', growth: 20 },
  { month: 'Nov', growth: 10 },
  { month: 'Dec', growth: 10 },
]

export function generateDemoData(): DashboardMetrics {
  return {
    totalSubscribers: 450,
    trendData: DEMO_TREND_DATA,
    statusDistribution: [
      { name: 'Active', value: 400 },
      { name: 'Inactive', value: 50 },
    ],
    monthlyGrowth: DEMO_MONTHLY_GROWTH,
    topCompanies: [
      { name: 'Acme Corp', subscribers: 85 },
      { name: 'TechStart Inc', subscribers: 72 },
      { name: 'Global Systems', subscribers: 58 },
      { name: 'DataFlow Ltd', subscribers: 43 },
      { name: 'CloudNet Pro', subscribers: 37 },
    ],
  }
}

import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { DashboardCharts } from '@/widgets/dashboard-charts'

// Mock demo data
const mockDemoData = {
  totalAccounts: 450,
  trendData: [
    { month: 'Jan', subscribers: 100 },
    { month: 'Feb', subscribers: 150 },
  ],
  statusDistribution: [
    { name: 'Active', value: 400 },
    { name: 'Inactive', value: 50 },
  ],
  monthlyGrowth: [
    { month: 'Jan', growth: 20 },
    { month: 'Feb', growth: 50 },
  ],
  topCompanies: [
    { name: 'Company A', subscribers: 50 },
    { name: 'Company B', subscribers: 40 },
  ],
}

vi.mock('@/entities/dashboard-metrics/model/demo-data', () => ({
  generateDemoData: () => mockDemoData,
}))

// Mock chart widgets
vi.mock('@/widgets/dashboard-charts/AreaChartWidget', () => ({
  AreaChartWidget: ({ data }: { data: any[] }) => (
    <div data-testid="area-chart">
      Area Chart: {data.length} points
    </div>
  ),
}))

vi.mock('@/widgets/dashboard-charts/PieChartWidget', () => ({
  PieChartWidget: ({ data }: { data: any[] }) => (
    <div data-testid="pie-chart">
      Pie Chart: {data.length} segments
    </div>
  ),
}))

vi.mock('@/widgets/dashboard-charts/BarChartWidget', () => ({
  BarChartWidget: ({ data }: { data: any[] }) => (
    <div data-testid="bar-chart">
      Bar Chart: {data.length} bars
    </div>
  ),
}))

vi.mock('@/widgets/dashboard-charts/TopCompaniesWidget', () => ({
  TopCompaniesWidget: ({ data }: { data: any[] }) => (
    <div data-testid="top-companies">
      Top Companies: {data.length} companies
    </div>
  ),
}))

describe('DashboardCharts', () => {
  it('should render all chart widgets', () => {
    render(<DashboardCharts />)
    expect(screen.getByTestId('area-chart')).toBeInTheDocument()
    expect(screen.getByTestId('pie-chart')).toBeInTheDocument()
    expect(screen.getByTestId('bar-chart')).toBeInTheDocument()
    expect(screen.getByTestId('top-companies')).toBeInTheDocument()
  })

  it('should pass trend data to area chart', () => {
    render(<DashboardCharts />)
    expect(screen.getByText(/Area Chart: 2 points/)).toBeInTheDocument()
  })

  it('should pass status distribution to pie chart', () => {
    render(<DashboardCharts />)
    expect(screen.getByText(/Pie Chart: 2 segments/)).toBeInTheDocument()
  })

  it('should pass monthly growth to bar chart', () => {
    render(<DashboardCharts />)
    expect(screen.getByText(/Bar Chart: 2 bars/)).toBeInTheDocument()
  })

  it('should pass top companies to top companies widget', () => {
    render(<DashboardCharts />)
    expect(screen.getByText(/Top Companies: 2 companies/)).toBeInTheDocument()
  })

  it('should have responsive grid layout', () => {
    const { container } = render(<DashboardCharts />)
    const grid = container.querySelector('[data-testid="dashboard-charts-grid"]')
    expect(grid).toBeInTheDocument()
  })
})

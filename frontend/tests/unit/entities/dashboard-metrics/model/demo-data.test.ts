import { describe, it, expect } from 'vitest'
import { generateDemoData } from '@/entities/dashboard-metrics/model/demo-data'
import type { DashboardMetrics } from '@/entities/dashboard-metrics/model/types'

describe('generateDemoData', () => {
  it('should return DashboardMetrics object', () => {
    const data = generateDemoData()
    expect(data).toBeDefined()
    expect(data).toHaveProperty('totalSubscribers')
    expect(data).toHaveProperty('trendData')
    expect(data).toHaveProperty('statusDistribution')
    expect(data).toHaveProperty('monthlyGrowth')
    expect(data).toHaveProperty('topCompanies')
  })

  it('should generate consistent total subscribers count', () => {
    const data = generateDemoData()
    expect(data.totalSubscribers).toBe(450)
    expect(typeof data.totalSubscribers).toBe('number')
  })

  it('should generate 12 months of trend data', () => {
    const data = generateDemoData()
    expect(data.trendData).toHaveLength(12)
    expect(data.trendData[0]).toHaveProperty('month')
    expect(data.trendData[0]).toHaveProperty('subscribers')
  })

  it('should generate status distribution with 2 segments', () => {
    const data = generateDemoData()
    expect(data.statusDistribution).toHaveLength(2)
    expect(data.statusDistribution[0]).toHaveProperty('name')
    expect(data.statusDistribution[0]).toHaveProperty('value')

    const names = data.statusDistribution.map(s => s.name)
    expect(names).toContain('Active')
    expect(names).toContain('Inactive')
  })

  it('should generate 12 months of monthly growth data', () => {
    const data = generateDemoData()
    expect(data.monthlyGrowth).toHaveLength(12)
    expect(data.monthlyGrowth[0]).toHaveProperty('month')
    expect(data.monthlyGrowth[0]).toHaveProperty('growth')
  })

  it('should generate top 5 companies', () => {
    const data = generateDemoData()
    expect(data.topCompanies).toHaveLength(5)
    expect(data.topCompanies[0]).toHaveProperty('name')
    expect(data.topCompanies[0]).toHaveProperty('subscribers')
  })

  it('should generate positive subscriber counts', () => {
    const data = generateDemoData()
    data.trendData.forEach(item => {
      expect(item.subscribers).toBeGreaterThan(0)
    })
    data.topCompanies.forEach(company => {
      expect(company.subscribers).toBeGreaterThan(0)
    })
  })

  it('should generate month names in trend data', () => {
    const data = generateDemoData()
    const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
    data.trendData.forEach((item, index) => {
      expect(item.month).toBe(months[index])
    })
  })

  it('should be deterministic (same output every time)', () => {
    const data1 = generateDemoData()
    const data2 = generateDemoData()
    expect(data1).toEqual(data2)
  })
})

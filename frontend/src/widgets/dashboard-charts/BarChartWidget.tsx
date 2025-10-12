/**
 * Bar Chart Widget
 *
 * Displays monthly growth data.
 * Uses Recharts BarChart with vertical bars.
 */

import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'
import type { MonthlyGrowthPoint } from '@/entities/dashboard-metrics/model/types'

interface BarChartWidgetProps {
  data: MonthlyGrowthPoint[]
}

export function BarChartWidget({ data }: BarChartWidgetProps) {
  return (
    <div className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
      <h3 className="mb-4 text-lg font-semibold text-gray-900">
        Monthly Growth
      </h3>
      <ResponsiveContainer width="100%" height={300}>
        <BarChart data={data}>
          <CartesianGrid strokeDasharray="3 3" />
          <XAxis dataKey="month" />
          <YAxis />
          <Tooltip />
          <Bar dataKey="growth" fill="hsl(var(--primary))" />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

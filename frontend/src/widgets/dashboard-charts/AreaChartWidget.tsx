/**
 * Area Chart Widget
 *
 * Displays subscriber growth trend over 12 months.
 * Uses Recharts AreaChart for smooth area visualization.
 */

import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'
import type { TrendDataPoint } from '@/entities/dashboard-metrics/model/types'

interface AreaChartWidgetProps {
  data: TrendDataPoint[]
}

export function AreaChartWidget({ data }: AreaChartWidgetProps) {
  return (
    <div className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
      <h3 className="mb-4 text-lg font-semibold text-gray-900">
        Account Growth Trend
      </h3>
      <ResponsiveContainer width="100%" height={300}>
        <AreaChart data={data}>
          <CartesianGrid strokeDasharray="3 3" />
          <XAxis dataKey="month" />
          <YAxis />
          <Tooltip />
          <Area
            type="monotone"
            dataKey="subscribers"
            stroke="hsl(var(--primary))"
            fill="hsl(var(--primary) / 0.2)"
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  )
}

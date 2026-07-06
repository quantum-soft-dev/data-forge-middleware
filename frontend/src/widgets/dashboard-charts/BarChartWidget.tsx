/**
 * Bar Chart Widget
 *
 * Displays monthly growth data.
 * Uses Recharts BarChart with vertical bars.
 */

import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'
import type { MonthlyGrowthPoint } from '@/entities/dashboard-metrics/model/types'
import { monitoringTokens } from '@/shared/ui/tokens'

/** Monitoring chart chrome (024, T015): 11px muted axis labels. */
const AXIS_TICK = { fill: monitoringTokens.textMuted, fontSize: 11 }

interface BarChartWidgetProps {
  data: MonthlyGrowthPoint[]
}

export function BarChartWidget({ data }: BarChartWidgetProps) {
  return (
    <div className="rounded-lg bg-white p-4 shadow-panel">
      <h3 className="mb-4 text-[15px] font-medium tracking-[-0.24px] text-ink-title">
        Monthly Growth
      </h3>
      <ResponsiveContainer width="100%" height={300}>
        <BarChart data={data}>
          <CartesianGrid strokeDasharray="3 3" stroke={monitoringTokens.separator} />
          <XAxis dataKey="month" tick={AXIS_TICK} axisLine={false} tickLine={false} />
          <YAxis tick={AXIS_TICK} axisLine={false} tickLine={false} />
          <Tooltip />
          <Bar dataKey="growth" fill="hsl(var(--primary))" />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

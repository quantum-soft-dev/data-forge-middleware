/**
 * Area Chart Widget
 *
 * Displays subscriber growth trend over 12 months.
 * Uses Recharts AreaChart for smooth area visualization.
 */

import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'
import type { TrendDataPoint } from '@/entities/dashboard-metrics/model/types'
import { monitoringTokens } from '@/shared/ui/tokens'

/** Monitoring chart chrome (024, T015): 11px muted axis labels. */
const AXIS_TICK = { fill: monitoringTokens.textMuted, fontSize: 11 }

interface AreaChartWidgetProps {
  data: TrendDataPoint[]
}

export function AreaChartWidget({ data }: AreaChartWidgetProps) {
  return (
    <div className="rounded-lg bg-white p-4 shadow-card">
      <h3 className="mb-4 text-[15px] font-medium tracking-[-0.24px] text-ink-title">
        Account Growth Trend
      </h3>
      <ResponsiveContainer width="100%" height={300}>
        <AreaChart data={data}>
          <CartesianGrid strokeDasharray="3 3" stroke={monitoringTokens.separator} />
          <XAxis dataKey="month" tick={AXIS_TICK} axisLine={false} tickLine={false} />
          <YAxis tick={AXIS_TICK} axisLine={false} tickLine={false} />
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

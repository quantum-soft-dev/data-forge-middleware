/**
 * Top Companies Widget
 *
 * Displays top 5 companies by subscriber count.
 * Uses Recharts BarChart with horizontal layout.
 */

import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'
import type { TopCompany } from '@/entities/dashboard-metrics/model/types'
import { monitoringTokens } from '@/shared/ui/tokens'

/** Monitoring chart chrome (024, T015): 11px muted axis labels. */
const AXIS_TICK = { fill: monitoringTokens.textMuted, fontSize: 11 }

interface TopCompaniesWidgetProps {
  data: TopCompany[]
}

export function TopCompaniesWidget({ data }: TopCompaniesWidgetProps) {
  return (
    <div className="rounded-lg bg-white p-4 shadow-card">
      <h3 className="mb-4 text-[15px] font-medium tracking-[-0.24px] text-ink-title">
        Top 5 Companies
      </h3>
      <ResponsiveContainer width="100%" height={300}>
        <BarChart data={data} layout="vertical">
          <CartesianGrid strokeDasharray="3 3" stroke={monitoringTokens.separator} />
          <XAxis type="number" tick={AXIS_TICK} axisLine={false} tickLine={false} />
          <YAxis type="category" dataKey="name" width={120} tick={AXIS_TICK} axisLine={false} tickLine={false} />
          <Tooltip />
          <Bar dataKey="subscribers" fill="hsl(var(--primary))" />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

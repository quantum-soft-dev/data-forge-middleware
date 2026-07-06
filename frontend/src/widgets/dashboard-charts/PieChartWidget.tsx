/**
 * Pie Chart Widget
 *
 * Displays status distribution (Active/Inactive subscribers).
 * Uses Recharts PieChart with custom colors.
 */

import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer, Legend } from 'recharts'
import type { StatusSegment } from '@/entities/dashboard-metrics/model/types'
import { monitoringTokens } from '@/shared/ui/tokens'

interface PieChartWidgetProps {
  data: StatusSegment[]
}

const COLORS = {
  Active: 'hsl(var(--primary))',
  Inactive: monitoringTokens.textMuted,
}

export function PieChartWidget({ data }: PieChartWidgetProps) {
  return (
    <div className="rounded-lg bg-white p-4 shadow-card">
      <h3 className="mb-4 text-[15px] font-medium tracking-[-0.24px] text-ink-title">
        Status Distribution
      </h3>
      <ResponsiveContainer width="100%" height={300}>
        <PieChart>
          <Pie
            data={data}
            cx="50%"
            cy="50%"
            labelLine={false}
            label={({ name, percent }) => `${name}: ${(percent * 100).toFixed(0)}%`}
            outerRadius={80}
            fill="#8884d8"
            dataKey="value"
          >
            {data.map((entry) => (
              <Cell key={`cell-${entry.name}`} fill={COLORS[entry.name]} />
            ))}
          </Pie>
          <Tooltip />
          <Legend />
        </PieChart>
      </ResponsiveContainer>
    </div>
  )
}

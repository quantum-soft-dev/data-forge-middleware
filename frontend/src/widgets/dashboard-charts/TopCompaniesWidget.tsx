/**
 * Top Companies Widget
 *
 * Displays top 5 companies by subscriber count.
 * Uses Recharts BarChart with horizontal layout.
 */

import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'
import type { TopCompany } from '@/entities/dashboard-metrics/model/types'

interface TopCompaniesWidgetProps {
  data: TopCompany[]
}

export function TopCompaniesWidget({ data }: TopCompaniesWidgetProps) {
  return (
    <div className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
      <h3 className="mb-4 text-lg font-semibold text-gray-900">
        Top 5 Companies
      </h3>
      <ResponsiveContainer width="100%" height={300}>
        <BarChart data={data} layout="vertical">
          <CartesianGrid strokeDasharray="3 3" />
          <XAxis type="number" />
          <YAxis type="category" dataKey="name" width={120} />
          <Tooltip />
          <Bar dataKey="subscribers" fill="hsl(var(--primary))" />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

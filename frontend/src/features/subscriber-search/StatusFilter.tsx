/**
 * StatusFilter Component
 *
 * Dropdown filter for subscriber status (All, Active, Inactive).
 */

import type { SubscriberStatus } from '@/entities/subscriber/model/types'

interface StatusFilterProps {
  value: SubscriberStatus | 'all'
  onChange: (status: SubscriberStatus | 'all') => void
}

export function StatusFilter({ value, onChange }: StatusFilterProps) {
  return (
    <div className="flex items-center gap-2">
      <label htmlFor="status-filter" className="text-sm font-medium text-gray-700">
        Status:
      </label>
      <select
        id="status-filter"
        value={value}
        onChange={(e) => onChange(e.target.value as SubscriberStatus | 'all')}
        className="rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
      >
        <option value="all">All</option>
        <option value="active">Active</option>
        <option value="inactive">Inactive</option>
      </select>
    </div>
  )
}

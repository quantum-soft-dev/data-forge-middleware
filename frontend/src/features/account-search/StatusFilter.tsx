/**
 * StatusFilter Component
 *
 * Dropdown filter for account status (All, Active, Inactive).
 */

import type { AccountStatus } from '@/entities/account/model/types'

interface StatusFilterProps {
  value: AccountStatus | 'all'
  onChange: (status: AccountStatus | 'all') => void
}

export function StatusFilter({ value, onChange }: StatusFilterProps) {
  return (
    <div className="flex items-center gap-2">
      <label htmlFor="status-filter" className="text-sm font-medium text-ink-secondary">
        Status:
      </label>
      <select
        id="status-filter"
        value={value}
        onChange={(e) => onChange(e.target.value as AccountStatus | 'all')}
        className="rounded-lg border border-hairline bg-white px-3 py-2 text-sm focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
      >
        <option value="all">All</option>
        <option value="active">Active</option>
        <option value="inactive">Inactive</option>
      </select>
    </div>
  )
}

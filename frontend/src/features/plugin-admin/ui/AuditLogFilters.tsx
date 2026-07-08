/**
 * AuditLogFilters Component
 *
 * Filter controls for audit log queries.
 */

import { useState } from 'react'
import { Filter, X } from 'lucide-react'
import { ACTION_TYPE_LABELS } from '@/entities/plugin/model/actionTypes'
import type { PluginActionType, AuditLogFilters } from '@/entities/plugin/model/types'

interface AuditLogFiltersProps {
  filters: AuditLogFilters
  pluginOptions?: string[]
  onFiltersChange: (filters: AuditLogFilters) => void
}

const ACTION_TYPES: PluginActionType[] = [
  'ACTIVATE',
  'DEACTIVATE',
  'REACTIVATE',
  'EVENT_DISPATCHED',
  'EVENT_FAILED',
  'EVENT_TIMEOUT',
]

export function AuditLogFiltersComponent({
  filters,
  pluginOptions = [],
  onFiltersChange,
}: AuditLogFiltersProps) {
  const [showFilters, setShowFilters] = useState(false)

  const hasActiveFilters =
    filters.pluginId ||
    filters.accountId ||
    filters.actionType ||
    filters.success !== undefined ||
    filters.from ||
    filters.to

  const handleClearFilters = () => {
    onFiltersChange({
      page: filters.page,
      size: filters.size,
    })
  }

  const handleFilterChange = (
    key: keyof AuditLogFilters,
    value: string | boolean | undefined
  ) => {
    onFiltersChange({
      ...filters,
      [key]: value,
      page: 0, // Reset to first page when filters change
    })
  }

  return (
    <div className="mb-6">
      <button
        onClick={() => setShowFilters(!showFilters)}
        className="flex items-center gap-2 rounded-lg border border-hairline bg-white px-4 py-2 text-sm font-medium text-ink hover:bg-secondary"
      >
        <Filter className="h-4 w-4" />
        Filters
        {hasActiveFilters && (
          <span className="ml-1 rounded-full bg-brand px-2 py-0.5 text-xs text-white">
            Active
          </span>
        )}
      </button>

      {showFilters && (
        <div className="mt-4 rounded-lg bg-white p-4 shadow-panel">
          <h3 className="mb-3 text-sm font-medium text-ink">Filter Options</h3>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {/* Plugin ID Filter */}
            <div>
              <label htmlFor="filter-plugin" className="mb-1 block text-xs font-medium text-ink-secondary">
                Plugin
              </label>
              <select
                id="filter-plugin"
                value={filters.pluginId || ''}
                onChange={(e) =>
                  handleFilterChange('pluginId', e.target.value || undefined)
                }
                className="h-9 w-full rounded-lg border border-input bg-background px-3 text-sm text-ink focus:outline-none focus:ring-2 focus:ring-ring"
              >
                <option value="">All Plugins</option>
                {pluginOptions.map((pluginId) => (
                  <option key={pluginId} value={pluginId}>
                    {pluginId}
                  </option>
                ))}
              </select>
            </div>

            {/* Account ID Filter */}
            <div>
              <label htmlFor="filter-account-id" className="mb-1 block text-xs font-medium text-ink-secondary">
                Account ID
              </label>
              <input
                id="filter-account-id"
                type="text"
                value={filters.accountId || ''}
                onChange={(e) =>
                  handleFilterChange('accountId', e.target.value || undefined)
                }
                placeholder="Enter account UUID"
                className="h-9 w-full rounded-lg border border-input bg-background px-3 text-sm text-ink focus:outline-none focus:ring-2 focus:ring-ring"
              />
            </div>

            {/* Action Type Filter */}
            <div>
              <label htmlFor="filter-action-type" className="mb-1 block text-xs font-medium text-ink-secondary">
                Action Type
              </label>
              <select
                id="filter-action-type"
                value={filters.actionType || ''}
                onChange={(e) =>
                  handleFilterChange(
                    'actionType',
                    (e.target.value as PluginActionType) || undefined
                  )
                }
                className="h-9 w-full rounded-lg border border-input bg-background px-3 text-sm text-ink focus:outline-none focus:ring-2 focus:ring-ring"
              >
                <option value="">All Actions</option>
                {ACTION_TYPES.map((type) => (
                  <option key={type} value={type}>
                    {ACTION_TYPE_LABELS[type]}
                  </option>
                ))}
              </select>
            </div>

            {/* Success Filter */}
            <div>
              <label htmlFor="filter-status" className="mb-1 block text-xs font-medium text-ink-secondary">
                Status
              </label>
              <select
                id="filter-status"
                value={filters.success === undefined ? '' : String(filters.success)}
                onChange={(e) =>
                  handleFilterChange(
                    'success',
                    e.target.value === '' ? undefined : e.target.value === 'true'
                  )
                }
                className="h-9 w-full rounded-lg border border-input bg-background px-3 text-sm text-ink focus:outline-none focus:ring-2 focus:ring-ring"
              >
                <option value="">All</option>
                <option value="true">Success</option>
                <option value="false">Failed</option>
              </select>
            </div>

            {/* Date Range - From */}
            <div>
              <label htmlFor="filter-from-date" className="mb-1 block text-xs font-medium text-ink-secondary">
                From Date
              </label>
              <input
                id="filter-from-date"
                type="datetime-local"
                value={filters.from?.slice(0, 16) || ''}
                onChange={(e) =>
                  handleFilterChange(
                    'from',
                    e.target.value ? new Date(e.target.value).toISOString() : undefined
                  )
                }
                className="h-9 w-full rounded-lg border border-input bg-background px-3 text-sm text-ink focus:outline-none focus:ring-2 focus:ring-ring"
              />
            </div>

            {/* Date Range - To */}
            <div>
              <label htmlFor="filter-to-date" className="mb-1 block text-xs font-medium text-ink-secondary">
                To Date
              </label>
              <input
                id="filter-to-date"
                type="datetime-local"
                value={filters.to?.slice(0, 16) || ''}
                onChange={(e) =>
                  handleFilterChange(
                    'to',
                    e.target.value ? new Date(e.target.value).toISOString() : undefined
                  )
                }
                className="h-9 w-full rounded-lg border border-input bg-background px-3 text-sm text-ink focus:outline-none focus:ring-2 focus:ring-ring"
              />
            </div>
          </div>

          {hasActiveFilters && (
            <button
              onClick={handleClearFilters}
              className="mt-3 flex items-center gap-1 text-sm text-brand hover:text-brand-hover"
            >
              <X className="h-4 w-4" />
              Clear all filters
            </button>
          )}
        </div>
      )}
    </div>
  )
}

/**
 * AuditLogFilters Component
 *
 * Filter controls for audit log queries.
 */

import { useState } from 'react'
import { Filter, X } from 'lucide-react'
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

const ACTION_TYPE_LABELS: Record<PluginActionType, string> = {
  ACTIVATE: 'Activate',
  DEACTIVATE: 'Deactivate',
  REACTIVATE: 'Reactivate',
  EVENT_DISPATCHED: 'Event Dispatched',
  EVENT_FAILED: 'Event Failed',
  EVENT_TIMEOUT: 'Event Timeout',
}

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
        className="flex items-center gap-2 rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
      >
        <Filter className="h-4 w-4" />
        Filters
        {hasActiveFilters && (
          <span className="ml-1 rounded-full bg-blue-600 px-2 py-0.5 text-xs text-white">
            Active
          </span>
        )}
      </button>

      {showFilters && (
        <div className="mt-4 rounded-lg border border-gray-200 bg-white p-4 shadow-sm">
          <h3 className="mb-3 text-sm font-medium text-gray-900">Filter Options</h3>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {/* Plugin ID Filter */}
            <div>
              <label htmlFor="filter-plugin" className="mb-1 block text-xs font-medium text-gray-700">
                Plugin
              </label>
              <select
                id="filter-plugin"
                value={filters.pluginId || ''}
                onChange={(e) =>
                  handleFilterChange('pluginId', e.target.value || undefined)
                }
                className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
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
              <label htmlFor="filter-account-id" className="mb-1 block text-xs font-medium text-gray-700">
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
                className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
            </div>

            {/* Action Type Filter */}
            <div>
              <label htmlFor="filter-action-type" className="mb-1 block text-xs font-medium text-gray-700">
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
                className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
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
              <label htmlFor="filter-status" className="mb-1 block text-xs font-medium text-gray-700">
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
                className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              >
                <option value="">All</option>
                <option value="true">Success</option>
                <option value="false">Failed</option>
              </select>
            </div>

            {/* Date Range - From */}
            <div>
              <label htmlFor="filter-from-date" className="mb-1 block text-xs font-medium text-gray-700">
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
                className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
            </div>

            {/* Date Range - To */}
            <div>
              <label htmlFor="filter-to-date" className="mb-1 block text-xs font-medium text-gray-700">
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
                className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
            </div>
          </div>

          {hasActiveFilters && (
            <button
              onClick={handleClearFilters}
              className="mt-3 flex items-center gap-1 text-sm text-blue-600 hover:text-blue-700"
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

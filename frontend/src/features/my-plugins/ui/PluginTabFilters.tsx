/**
 * PluginTabFilters Component
 *
 * Filter controls for plugin logs and batch SQL tabs.
 * Provides site filtering, date range, and page size selection.
 */

import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Filter, X } from 'lucide-react'
import { Button } from '@/shared/ui/ui/button'
import { Label } from '@/shared/ui/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/shared/ui/ui/select'
import { listUserSites } from '@/entities/site/api/siteApi'
import type { Site } from '@/entities/site/model/types'

/**
 * Filter state for logs tab (includes date range).
 */
export interface LogsFilterState {
  siteId?: string
  from?: string
  to?: string
  size: number
}

/**
 * Filter state for SQL tab (no date range).
 */
export interface SqlFilterState {
  siteId?: string
  size: number
}

interface PluginTabFiltersProps {
  /** Current filter state */
  filters: LogsFilterState | SqlFilterState
  /** Callback when filters change */
  onFiltersChange: (filters: LogsFilterState | SqlFilterState) => void
  /** Show date range filters (for logs tab) */
  showDateRange?: boolean
  /** Available page sizes */
  pageSizes?: number[]
}

const DEFAULT_PAGE_SIZES = [20, 30, 50, 100]

/**
 * Hook to fetch user's sites for the filter dropdown.
 */
function useSitesQuery() {
  return useQuery({
    queryKey: ['sites', 'user'],
    queryFn: listUserSites,
    staleTime: 60000, // 1 minute
  })
}

export function PluginTabFilters({
  filters,
  onFiltersChange,
  showDateRange = false,
  pageSizes = DEFAULT_PAGE_SIZES,
}: PluginTabFiltersProps) {
  const [showFilters, setShowFilters] = useState(false)
  const { data: sites = [], isLoading: sitesLoading } = useSitesQuery()

  // Check if any filters are active
  const hasActiveFilters =
    filters.siteId ||
    (showDateRange && 'from' in filters && filters.from) ||
    (showDateRange && 'to' in filters && filters.to)

  const handleSiteChange = (value: string) => {
    onFiltersChange({
      ...filters,
      siteId: value === 'all' ? undefined : value,
    })
  }

  const handleFromChange = (value: string) => {
    if (showDateRange) {
      onFiltersChange({
        ...filters,
        from: value || undefined,
      } as LogsFilterState)
    }
  }

  const handleToChange = (value: string) => {
    if (showDateRange) {
      onFiltersChange({
        ...filters,
        to: value || undefined,
      } as LogsFilterState)
    }
  }

  const handleSizeChange = (value: string) => {
    onFiltersChange({
      ...filters,
      size: parseInt(value, 10),
    })
  }

  const handleClearFilters = () => {
    if (showDateRange) {
      onFiltersChange({
        siteId: undefined,
        from: undefined,
        to: undefined,
        size: filters.size,
      } as LogsFilterState)
    } else {
      onFiltersChange({
        siteId: undefined,
        size: filters.size,
      } as SqlFilterState)
    }
  }

  return (
    <div className="mb-4">
      <div className="flex items-center gap-2">
        <Button
          variant="outline"
          size="sm"
          onClick={() => setShowFilters(!showFilters)}
          className="gap-2"
        >
          <Filter className="h-4 w-4" />
          Filters
          {hasActiveFilters && (
            <span className="ml-1 rounded-full bg-blue-600 px-2 py-0.5 text-xs text-white">
              Active
            </span>
          )}
        </Button>

        {/* Page size selector - always visible */}
        <div className="ml-auto flex items-center gap-2">
          <Label htmlFor="page-size" className="text-xs text-gray-500">
            Show:
          </Label>
          <Select value={String(filters.size)} onValueChange={handleSizeChange}>
            <SelectTrigger id="page-size" className="h-8 w-20">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {pageSizes.map((size) => (
                <SelectItem key={size} value={String(size)}>
                  {size}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </div>

      {showFilters && (
        <div className="mt-3 rounded-lg border border-gray-200 bg-white p-4 shadow-sm">
          <h3 className="mb-3 text-sm font-medium text-gray-900">Filter Options</h3>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {/* Site Filter */}
            <div>
              <Label htmlFor="filter-site" className="mb-1 block text-xs font-medium text-gray-700">
                Site
              </Label>
              <Select
                value={filters.siteId || 'all'}
                onValueChange={handleSiteChange}
                disabled={sitesLoading}
              >
                <SelectTrigger id="filter-site" className="w-full">
                  <SelectValue placeholder={sitesLoading ? 'Loading...' : 'All Sites'} />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">All Sites</SelectItem>
                  {sites.map((site: Site) => (
                    <SelectItem key={site.id} value={site.id}>
                      {site.domain}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            {/* Date Range - From (only for logs) */}
            {showDateRange && (
              <div>
                <Label htmlFor="filter-from" className="mb-1 block text-xs font-medium text-gray-700">
                  From Date
                </Label>
                <input
                  id="filter-from"
                  type="datetime-local"
                  value={(filters as LogsFilterState).from?.slice(0, 16) || ''}
                  onChange={(e) => {
                    const value = e.target.value
                    handleFromChange(value ? new Date(value).toISOString() : '')
                  }}
                  className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                />
              </div>
            )}

            {/* Date Range - To (only for logs) */}
            {showDateRange && (
              <div>
                <Label htmlFor="filter-to" className="mb-1 block text-xs font-medium text-gray-700">
                  To Date
                </Label>
                <input
                  id="filter-to"
                  type="datetime-local"
                  value={(filters as LogsFilterState).to?.slice(0, 16) || ''}
                  onChange={(e) => {
                    const value = e.target.value
                    handleToChange(value ? new Date(value).toISOString() : '')
                  }}
                  className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                />
              </div>
            )}
          </div>

          {hasActiveFilters && (
            <Button
              variant="link"
              size="sm"
              onClick={handleClearFilters}
              className="mt-3 h-auto p-0 text-blue-600 hover:text-blue-700"
            >
              <X className="mr-1 h-4 w-4" />
              Clear all filters
            </Button>
          )}
        </div>
      )}
    </div>
  )
}

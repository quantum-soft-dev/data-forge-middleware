/**
 * AuditLogWidget
 *
 * Container component for the audit log section with filters.
 */

import { useState, useMemo } from 'react'
import { AuditLogFiltersComponent } from '@/features/plugin-admin/ui/AuditLogFilters'
import { AuditLogTable } from '@/features/plugin-admin/ui/AuditLogTable'
import {
  useAuditLogsQuery,
  usePluginsQuery,
} from '@/features/plugin-admin/api/pluginQueries'
import type { AuditLogFilters } from '@/entities/plugin/model/types'

interface AuditLogWidgetProps {
  initialPluginId?: string
}

export function AuditLogWidget({ initialPluginId }: AuditLogWidgetProps) {
  const [filters, setFilters] = useState<AuditLogFilters>({
    page: 0,
    size: 50,
    pluginId: initialPluginId,
  })

  const { data: plugins } = usePluginsQuery()
  const { data: auditLogs, isLoading, isError, error } = useAuditLogsQuery(filters)

  const pluginOptions = useMemo(
    () => plugins?.map((p) => p.pluginId) || [],
    [plugins]
  )

  const handlePageChange = (newPage: number) => {
    setFilters((prev) => ({ ...prev, page: newPage - 1 })) // Convert to 0-indexed
  }

  const handlePageSizeChange = (newSize: number) => {
    setFilters((prev) => ({ ...prev, size: newSize, page: 0 }))
  }

  if (isError) {
    return (
      <div className="rounded-lg border border-danger-border bg-danger-bg p-4">
        <p className="text-sm font-medium text-danger-text">
          {error instanceof Error ? error.message : 'Failed to load audit logs'}
        </p>
      </div>
    )
  }

  return (
    <div>
      <AuditLogFiltersComponent
        filters={filters}
        pluginOptions={pluginOptions}
        onFiltersChange={setFilters}
      />

      <AuditLogTable
        logs={auditLogs?.content || []}
        totalCount={auditLogs?.totalElements || 0}
        page={(filters.page || 0) + 1} // Convert to 1-indexed for UI
        pageSize={filters.size || 50}
        totalPages={auditLogs?.totalPages || 0}
        onPageChange={handlePageChange}
        onPageSizeChange={handlePageSizeChange}
        isLoading={isLoading}
      />
    </div>
  )
}

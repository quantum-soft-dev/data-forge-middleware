/**
 * PluginLogsTab Component
 *
 * Displays a list of plugin log entries with status badges and metadata.
 * Used in the My Plugins widget on the Dashboard.
 *
 * Features:
 * - Shows all plugin events (activation, SQL generation, errors)
 * - Color-coded status badges (success/failed)
 * - SQL generation statistics (INSERT/UPDATE/DELETE counts)
 * - Site information for SQL-related events
 * - Filtering by site and date range
 * - Configurable page size (20, 30, 50, 100)
 */

import { useState, useCallback } from 'react'
import { Loader2, FileText, AlertCircle, CheckCircle2, XCircle, Clock, ChevronLeft, ChevronRight } from 'lucide-react'
import { Badge } from '@/shared/ui/ui/badge'
import { severityTokens } from '@/shared/ui/tokens'
import { Button } from '@/shared/ui/ui/button'
import { usePluginLogsQuery } from '../api/pluginLogsQueries'
import { PluginTabFilters, type LogsFilterState } from './PluginTabFilters'
import type { PluginLogEntry, PluginActionType, SqlGenerationMetadata } from '../model/types'

/**
 * Action types visible to end users.
 * Technical events (EVENT_DISPATCHED, EVENT_FAILED, EVENT_TIMEOUT) are hidden.
 */
const USER_VISIBLE_ACTIONS: PluginActionType[] = [
  'ACTIVATE',
  'DEACTIVATE',
  'REACTIVATE',
  'SQL_GENERATION_STARTED',
  'SQL_GENERATION_COMPLETED',
  'SQL_GENERATION_FAILED',
  'SQL_GENERATION_ADOPTED',
  'SQL_REGENERATION_STARTED',
  'SQL_REGENERATION_COMPLETED',
  'SQL_REGENERATION_FAILED',
  'REINIT',
  'PLUGIN_HISTORY_CLEARED',
  // Credential rotations are security-relevant: the owner must be able to see when their own
  // key or password changed, and when it did not.
  'API_KEY_ROTATED',
  'PASSWORD_ROTATED',
  // Parquet Export activity. Written since 028 but rejected by the audit CHECK constraint until
  // V44, so these only start appearing now.
  'FILES_LISTED',
  'LINK_CONSUMED',
  'LINK_REJECTED',
]

interface PluginLogsTabProps {
  /** Plugin identifier (e.g., "bit-bi") */
  pluginId: string
}

/**
 * User-friendly labels for action types.
 */
const ACTION_TYPE_LABELS: Record<PluginActionType, string> = {
  ACTIVATE: 'Plugin Activated',
  DEACTIVATE: 'Plugin Deactivated',
  REACTIVATE: 'Plugin Reactivated',
  SQL_GENERATION_STARTED: 'Generating SQL...',
  SQL_GENERATION_COMPLETED: 'SQL Generated',
  SQL_GENERATION_FAILED: 'SQL Generation Failed',
  SQL_GENERATION_ADOPTED: 'SQL Already Generated',
  EVENT_DISPATCHED: 'Event Dispatched',
  EVENT_FAILED: 'Event Failed',
  EVENT_TIMEOUT: 'Event Timeout',
  SQL_GENERATION_DELETED: 'SQL Generation Deleted',
  SQL_REGENERATION_STARTED: 'Regenerating SQL...',
  SQL_REGENERATION_COMPLETED: 'SQL Regenerated',
  SQL_REGENERATION_FAILED: 'SQL Regeneration Failed',
  PLUGIN_HISTORY_CLEARED: 'History Cleared',
  REINIT: 'Plugin Reinitialized',
  FILES_LISTED: 'Files Listed',
  LINK_CONSUMED: 'Download Link Used',
  LINK_REJECTED: 'Download Link Rejected',
  PASSWORD_ROTATED: 'Password Rotated',
  API_KEY_ROTATED: 'API Key Rotated',
}

/**
 * Format action type for display using user-friendly labels.
 */
function formatActionType(actionType: PluginActionType): string {
  return ACTION_TYPE_LABELS[actionType] || actionType
}

/**
 * Format timestamp for display.
 */
function formatTimestamp(isoString: string): string {
  const date = new Date(isoString)
  return date.toLocaleString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/**
 * Get icon for action type.
 */
function getActionIcon(actionType: PluginActionType, success: boolean) {
  if (!success) {
    return <XCircle className="h-4 w-4 text-danger-solid" />
  }

  switch (actionType) {
    case 'SQL_GENERATION_COMPLETED':
    case 'SQL_GENERATION_ADOPTED':
      return <CheckCircle2 className="h-4 w-4" style={{ color: severityTokens.healthy.dot }} strokeWidth={1.5} />
    case 'SQL_GENERATION_STARTED':
      return <Clock className="h-4 w-4 text-blue-500" />
    case 'SQL_GENERATION_FAILED':
      return <XCircle className="h-4 w-4 text-danger-solid" />
    case 'ACTIVATE':
    case 'DEACTIVATE':
      return <FileText className="h-4 w-4 text-ink-secondary" />
    default:
      return <FileText className="h-4 w-4 text-ink-secondary" />
  }
}

/**
 * SQL Generation Stats display component.
 */
function SqlGenerationStats({ metadata }: { metadata: SqlGenerationMetadata }) {
  const { insertCount, updateCount, deleteCount, durationMs } = metadata

  const hasStats = insertCount !== undefined || updateCount !== undefined || deleteCount !== undefined

  if (!hasStats) {
    return null
  }

  return (
    <div className="mt-2 flex flex-wrap gap-2 text-xs text-ink-secondary">
      {insertCount !== undefined && (
        <span className="rounded-full px-2 py-0.5 tabular-nums" style={{ background: severityTokens.healthy.bg, color: severityTokens.healthy.text }}>
          +{insertCount} INSERT
        </span>
      )}
      {updateCount !== undefined && (
        <span className="rounded bg-blue-100 px-2 py-0.5 text-blue-700">
          ~{updateCount} UPDATE
        </span>
      )}
      {deleteCount !== undefined && (
        <span className="rounded-full px-2 py-0.5 tabular-nums" style={{ background: severityTokens.critical.bg, color: severityTokens.critical.text }}>
          -{deleteCount} DELETE
        </span>
      )}
      {durationMs !== undefined && (
        <span className="rounded-full bg-surface-subtle px-2 py-0.5 text-ink-secondary tabular-nums">
          {durationMs}ms
        </span>
      )}
    </div>
  )
}

/**
 * Single log entry component.
 */
function LogEntry({ entry }: { entry: PluginLogEntry }) {
  const { actionType, success, errorMessage, metadata, occurredAt, siteDomain } = entry

  return (
    <div className="rounded-lg bg-white p-4 shadow-panel">
      <div className="flex items-start justify-between">
        <div className="flex flex-wrap items-center gap-2">
          {getActionIcon(actionType, success)}
          <span className="font-medium text-ink">
            {formatActionType(actionType)}
          </span>
          <Badge variant={success ? 'success' : 'critical'} dot>
            {success ? 'Success' : 'Failed'}
          </Badge>
          {siteDomain && (
            <Badge variant="neutral">
              {siteDomain}
            </Badge>
          )}
        </div>
        <span className="text-xs text-ink-secondary whitespace-nowrap ml-2">{formatTimestamp(occurredAt)}</span>
      </div>

      {errorMessage && (
        <div className="mt-2 flex items-start gap-2 rounded-lg bg-danger-bg p-2 text-sm text-danger-text">
          <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0" />
          <span>{errorMessage}</span>
        </div>
      )}

      {metadata && 'insertCount' in metadata && (
        <SqlGenerationStats metadata={metadata as SqlGenerationMetadata} />
      )}
    </div>
  )
}

export function PluginLogsTab({ pluginId }: PluginLogsTabProps) {
  const [page, setPage] = useState(0)
  const [filters, setFilters] = useState<LogsFilterState>({
    size: 20,
  })

  const { data, isLoading, isError, error } = usePluginLogsQuery({
    pluginId,
    page,
    size: filters.size,
    siteId: filters.siteId,
    from: filters.from,
    to: filters.to,
  })

  // Reset page when filters change
  const handleFiltersChange = useCallback((newFilters: LogsFilterState) => {
    setFilters(newFilters)
    setPage(0) // Reset to first page when filters change
  }, [])

  const handlePrevPage = useCallback(() => {
    setPage((p) => Math.max(0, p - 1))
  }, [])

  const handleNextPage = useCallback(() => {
    if (data && page < data.totalPages - 1) {
      setPage((p) => p + 1)
    }
  }, [data, page])

  return (
    <div>
      <PluginTabFilters
        filters={filters}
        onFiltersChange={handleFiltersChange}
        showDateRange={true}
      />

      {isLoading ? (
        <div className="flex items-center justify-center py-8">
          <Loader2 className="h-8 w-8 animate-spin text-ink-muted" />
          <span className="ml-3 text-sm text-ink-secondary">Loading logs...</span>
        </div>
      ) : isError ? (
        <div className="rounded-lg border border-danger-border bg-danger-bg p-4 text-center">
          <p className="text-sm text-danger-text">
            Failed to fetch logs: {error?.message || 'Unknown error'}
          </p>
        </div>
      ) : (
        <>
          {/* Filter out technical events that are not meaningful to users */}
          {(() => {
            const visibleLogs = data?.content.filter((log) =>
              USER_VISIBLE_ACTIONS.includes(log.actionType)
            ) ?? []

            if (!data || visibleLogs.length === 0) {
              return (
                <div className="rounded-lg bg-white p-8 text-center shadow-panel">
                  <FileText className="mx-auto h-12 w-12 text-ink-muted" />
                  <p className="mt-4 text-sm text-ink-secondary">No log entries</p>
                  <p className="text-xs text-ink-muted">
                    {filters.siteId || filters.from || filters.to
                      ? 'Try adjusting your filters.'
                      : 'Plugin activity will appear here once you start using it.'}
                  </p>
                </div>
              )
            }

            return (
              <div className="space-y-4">
                {visibleLogs.map((entry) => (
                  <LogEntry key={entry.id} entry={entry} />
                ))}

                {/* Pagination */}
                {data.totalPages > 1 && (
                  <div className="flex items-center justify-between border-t border-separator pt-4">
                    <div className="text-sm text-ink-secondary">
                      Page {data.page + 1} of {data.totalPages} ({data.totalElements} total)
                    </div>
                    <div className="flex items-center gap-2">
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={handlePrevPage}
                        disabled={page === 0}
                      >
                        <ChevronLeft className="h-4 w-4" />
                        Previous
                      </Button>
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={handleNextPage}
                        disabled={page >= data.totalPages - 1}
                      >
                        Next
                        <ChevronRight className="h-4 w-4" />
                      </Button>
                    </div>
                  </div>
                )}
              </div>
            )
          })()}
        </>
      )}
    </div>
  )
}

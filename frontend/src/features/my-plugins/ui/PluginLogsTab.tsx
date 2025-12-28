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
 * - Formatted timestamps
 */

import { Loader2, FileText, AlertCircle, CheckCircle2, XCircle, Clock } from 'lucide-react'
import { Badge } from '@/shared/ui/ui/badge'
import { usePluginLogsQuery } from '../api/pluginLogsQueries'
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
]

interface PluginLogsTabProps {
  /** Plugin identifier (e.g., "bit-bi") */
  pluginId: string
  /** Current page number (0-indexed) */
  page?: number
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
  EVENT_DISPATCHED: 'Event Dispatched',
  EVENT_FAILED: 'Event Failed',
  EVENT_TIMEOUT: 'Event Timeout',
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
    return <XCircle className="h-4 w-4 text-red-500" />
  }

  switch (actionType) {
    case 'SQL_GENERATION_COMPLETED':
      return <CheckCircle2 className="h-4 w-4 text-green-500" />
    case 'SQL_GENERATION_STARTED':
      return <Clock className="h-4 w-4 text-blue-500" />
    case 'SQL_GENERATION_FAILED':
      return <XCircle className="h-4 w-4 text-red-500" />
    case 'ACTIVATE':
    case 'DEACTIVATE':
      return <FileText className="h-4 w-4 text-gray-500" />
    default:
      return <FileText className="h-4 w-4 text-gray-500" />
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
    <div className="mt-2 flex flex-wrap gap-2 text-xs text-gray-600">
      {insertCount !== undefined && (
        <span className="rounded bg-green-100 px-2 py-0.5 text-green-700">
          +{insertCount} INSERT
        </span>
      )}
      {updateCount !== undefined && (
        <span className="rounded bg-blue-100 px-2 py-0.5 text-blue-700">
          ~{updateCount} UPDATE
        </span>
      )}
      {deleteCount !== undefined && (
        <span className="rounded bg-red-100 px-2 py-0.5 text-red-700">
          -{deleteCount} DELETE
        </span>
      )}
      {durationMs !== undefined && (
        <span className="rounded bg-gray-100 px-2 py-0.5 text-gray-600">
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
  const { actionType, success, errorMessage, metadata, occurredAt } = entry

  return (
    <div className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm">
      <div className="flex items-start justify-between">
        <div className="flex items-center gap-2">
          {getActionIcon(actionType, success)}
          <span className="font-medium text-gray-900">
            {formatActionType(actionType)}
          </span>
          <Badge
            variant={success ? 'default' : 'destructive'}
            className={success ? 'bg-green-100 text-green-700 hover:bg-green-100' : ''}
          >
            {success ? 'Success' : 'Failed'}
          </Badge>
        </div>
        <span className="text-xs text-gray-500">{formatTimestamp(occurredAt)}</span>
      </div>

      {errorMessage && (
        <div className="mt-2 flex items-start gap-2 rounded bg-red-50 p-2 text-sm text-red-700">
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

export function PluginLogsTab({ pluginId, page = 0 }: PluginLogsTabProps) {
  const { data, isLoading, isError, error } = usePluginLogsQuery(pluginId, page)

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-8">
        <Loader2 className="h-8 w-8 animate-spin text-gray-400" />
        <span className="ml-3 text-sm text-gray-500">Loading logs...</span>
      </div>
    )
  }

  if (isError) {
    return (
      <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-center">
        <p className="text-sm text-red-600">
          Failed to fetch logs: {error?.message || 'Unknown error'}
        </p>
      </div>
    )
  }

  // Filter out technical events that are not meaningful to users
  const visibleLogs = data?.content.filter((log) =>
    USER_VISIBLE_ACTIONS.includes(log.actionType)
  ) ?? []

  if (!data || visibleLogs.length === 0) {
    return (
      <div className="rounded-lg border border-gray-200 bg-gray-50 p-8 text-center">
        <FileText className="mx-auto h-12 w-12 text-gray-400" />
        <p className="mt-4 text-sm text-gray-500">No log entries</p>
        <p className="text-xs text-gray-400">
          Plugin activity will appear here once you start using it.
        </p>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      {visibleLogs.map((entry) => (
        <LogEntry key={entry.id} entry={entry} />
      ))}

      {data.totalPages > 1 && (
        <div className="flex items-center justify-center gap-2 pt-4 text-sm text-gray-500">
          <span>
            Page {data.page + 1} of {data.totalPages}
          </span>
          <span className="text-gray-400">|</span>
          <span>{data.totalElements} total entries</span>
        </div>
      )}
    </div>
  )
}

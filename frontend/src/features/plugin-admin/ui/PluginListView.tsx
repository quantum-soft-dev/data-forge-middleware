/**
 * PluginListView Component
 *
 * Displays list of registered plugins with status and capabilities.
 */

import { formatDistanceToNow } from 'date-fns'
import { Plug, Calendar, Settings, FileText, Database } from 'lucide-react'
import type { PluginConfig } from '@/entities/plugin/model/types'
import { PluginStatusBadge } from '@/entities/plugin/ui/PluginStatusBadge'
import { Button } from '@/shared/ui/ui/button'

interface PluginListViewProps {
  plugins: PluginConfig[]
  isLoading?: boolean
  onPluginClick?: (pluginId: string) => void
  onHistoryClick?: (pluginId: string) => void
}

export function PluginListView({
  plugins,
  isLoading = false,
  onPluginClick,
  onHistoryClick,
}: PluginListViewProps) {
  if (isLoading) {
    return (
      <div className="rounded-lg border border-gray-200 bg-white p-8">
        <div className="flex items-center justify-center">
          <div className="h-8 w-8 animate-spin rounded-full border-4 border-gray-200 border-t-blue-600" />
          <span className="ml-3 text-sm text-gray-600">Loading plugins...</span>
        </div>
      </div>
    )
  }

  if (plugins.length === 0) {
    return (
      <div className="rounded-lg border border-gray-200 bg-white p-8 text-center">
        <Plug className="mx-auto h-12 w-12 text-gray-400" />
        <p className="mt-4 text-sm text-gray-500">No plugins registered</p>
      </div>
    )
  }

  return (
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {plugins.map((plugin) => (
        <div
          key={plugin.pluginId}
          className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm transition-shadow hover:shadow-md"
        >
          <div className="flex items-start justify-between">
            <div className="flex items-center gap-3">
              <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-blue-50">
                <Plug className="h-5 w-5 text-blue-600" />
              </div>
              <div>
                <h3 className="text-sm font-medium text-gray-900">
                  {plugin.displayName}
                </h3>
                <p className="text-xs text-gray-500">{plugin.pluginId}</p>
              </div>
            </div>
            <PluginStatusBadge isEnabled={plugin.isEnabled} />
          </div>

          <div className="mt-4 space-y-2">
            <div className="flex items-center gap-2 text-xs text-gray-500">
              <Settings className="h-3.5 w-3.5" />
              <span>Version: {plugin.version}</span>
            </div>
            <div className="flex items-center gap-2 text-xs text-gray-500">
              <Calendar className="h-3.5 w-3.5" />
              <span>
                Created{' '}
                {formatDistanceToNow(new Date(plugin.createdAt), { addSuffix: true })}
              </span>
            </div>
          </div>

          {plugin.supportedEvents.length > 0 && (
            <div className="mt-3">
              <p className="mb-1 text-xs font-medium text-gray-700">Supported Events:</p>
              <div className="flex flex-wrap gap-1">
                {plugin.supportedEvents.map((event) => (
                  <span
                    key={event}
                    className="rounded bg-gray-100 px-2 py-0.5 text-xs text-gray-600"
                  >
                    {event}
                  </span>
                ))}
              </div>
            </div>
          )}

          {/* Action buttons */}
          <div className="mt-4 flex gap-2 border-t pt-3">
            {onPluginClick && (
              <Button
                variant="outline"
                size="sm"
                className="flex-1"
                onClick={() => onPluginClick(plugin.pluginId)}
              >
                <FileText className="mr-1 h-3.5 w-3.5" />
                Audit Logs
              </Button>
            )}
            {onHistoryClick && (
              <Button
                variant="outline"
                size="sm"
                className="flex-1"
                onClick={() => onHistoryClick(plugin.pluginId)}
              >
                <Database className="mr-1 h-3.5 w-3.5" />
                SQL History
              </Button>
            )}
          </div>
        </div>
      ))}
    </div>
  )
}

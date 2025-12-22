/**
 * PluginCard Component
 *
 * Displays a single plugin card with activation status and actions.
 * Used in the My Plugins widget on the Dashboard.
 */

import { formatDistanceToNow } from 'date-fns'
import { Plug, Clock, Calendar, Check, X } from 'lucide-react'
import { Button } from '@/shared/ui/ui/button'
import { Badge } from '@/shared/ui/ui/badge'
import type { AccountPluginSummary, AvailablePlugin } from '../model/types'

interface PluginCardProps {
  /** Plugin summary for activated plugins */
  plugin?: AccountPluginSummary
  /** Available plugin for unactivated plugins */
  availablePlugin?: AvailablePlugin
  /** Whether the plugin is currently activated for this account */
  isActivated?: boolean
  /** Callback when activate button is clicked */
  onActivate?: (pluginId: string) => void
  /** Callback when deactivate button is clicked */
  onDeactivate?: (pluginId: string) => void
  /** Whether an operation is pending */
  isPending?: boolean
}

export function PluginCard({
  plugin,
  availablePlugin,
  isActivated = false,
  onActivate,
  onDeactivate,
  isPending = false,
}: PluginCardProps) {
  const pluginId = plugin?.pluginId || availablePlugin?.pluginId || ''
  const pluginName = plugin?.pluginName || availablePlugin?.displayName || ''
  const isActive = plugin?.isActive ?? isActivated
  const version = availablePlugin?.version

  return (
    <div className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm transition-shadow hover:shadow-md">
      {/* Header */}
      <div className="flex items-start justify-between">
        <div className="flex items-center gap-3">
          <div className={`flex h-10 w-10 items-center justify-center rounded-lg ${
            isActive ? 'bg-green-50' : 'bg-gray-100'
          }`}>
            <Plug className={`h-5 w-5 ${isActive ? 'text-green-600' : 'text-gray-400'}`} />
          </div>
          <div>
            <h3 className="text-sm font-medium text-gray-900">{pluginName}</h3>
            {version && (
              <p className="text-xs text-gray-500">v{version}</p>
            )}
          </div>
        </div>
        <Badge
          variant={isActive ? 'default' : 'secondary'}
          className={isActive ? 'bg-green-100 text-green-800 hover:bg-green-100' : ''}
        >
          {isActive ? (
            <span className="flex items-center gap-1">
              <Check className="h-3 w-3" />
              Active
            </span>
          ) : (
            <span className="flex items-center gap-1">
              <X className="h-3 w-3" />
              Inactive
            </span>
          )}
        </Badge>
      </div>

      {/* Metadata */}
      <div className="mt-4 space-y-2">
        {plugin?.activatedAt && (
          <div className="flex items-center gap-2 text-xs text-gray-500">
            <Calendar className="h-3.5 w-3.5" />
            <span>
              Activated{' '}
              {formatDistanceToNow(new Date(plugin.activatedAt), { addSuffix: true })}
            </span>
          </div>
        )}
        {plugin?.lastUsedAt && (
          <div className="flex items-center gap-2 text-xs text-gray-500">
            <Clock className="h-3.5 w-3.5" />
            <span>
              Last used{' '}
              {formatDistanceToNow(new Date(plugin.lastUsedAt), { addSuffix: true })}
            </span>
          </div>
        )}
      </div>

      {/* Actions */}
      <div className="mt-4">
        {isActive ? (
          <Button
            variant="outline"
            size="sm"
            className="w-full text-red-600 hover:bg-red-50 hover:text-red-700"
            onClick={() => onDeactivate?.(pluginId)}
            disabled={isPending}
          >
            {isPending ? 'Deactivating...' : 'Deactivate'}
          </Button>
        ) : (
          <Button
            variant="outline"
            size="sm"
            className="w-full text-green-600 hover:bg-green-50 hover:text-green-700"
            onClick={() => onActivate?.(pluginId)}
            disabled={isPending}
          >
            {isPending ? 'Activating...' : 'Activate'}
          </Button>
        )}
      </div>
    </div>
  )
}

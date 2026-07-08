/**
 * PluginCard Component
 *
 * Displays a single plugin card with activation status and actions.
 * Used in the My Plugins widget on the Dashboard.
 */

import { formatDistanceToNow } from 'date-fns'
import { Plug, Clock, Calendar } from 'lucide-react'
import { Button } from '@/shared/ui/ui/button'
import { Badge } from '@/shared/ui/ui/badge'
import { monitoringTokens, severityTokens } from '@/shared/ui/tokens'
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
    <div className="rounded-lg bg-white p-4 shadow-panel">
      {/* Header */}
      <div className="flex items-start justify-between">
        <div className="flex items-center gap-3">
          <div
            className="flex h-10 w-10 items-center justify-center rounded-full border bg-white"
            style={{ borderColor: monitoringTokens.iconWellBorder, boxShadow: monitoringTokens.iconCircleShadow }}
          >
            <Plug className="h-5 w-5" style={{ color: isActive ? severityTokens.healthy.dot : monitoringTokens.textMuted }} strokeWidth={1.5} />
          </div>
          <div>
            <h3 className="text-sm font-medium text-ink">{pluginName}</h3>
            {version && (
              <p className="text-xs text-ink-secondary">v{version}</p>
            )}
          </div>
        </div>
        <Badge variant={isActive ? 'success' : 'neutral'} dot>
          {isActive ? 'Active' : 'Inactive'}
        </Badge>
      </div>

      {/* Metadata */}
      <div className="mt-4 space-y-2">
        {plugin?.activatedAt && (
          <div className="flex items-center gap-2 text-xs text-ink-secondary">
            <Calendar className="h-3.5 w-3.5" />
            <span>
              Activated{' '}
              {formatDistanceToNow(new Date(plugin.activatedAt), { addSuffix: true })}
            </span>
          </div>
        )}
        {plugin?.lastUsedAt && (
          <div className="flex items-center gap-2 text-xs text-ink-secondary">
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
            variant="destructive-outline"
            size="sm"
            className="w-full"
            onClick={() => onDeactivate?.(pluginId)}
            disabled={isPending}
          >
            {isPending ? 'Deactivating...' : 'Deactivate'}
          </Button>
        ) : (
          <Button
            size="sm"
            className="w-full"
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

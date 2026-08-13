/**
 * PluginList Component
 *
 * Displays a list of plugin cards with loading and empty states.
 * Used in the My Plugins widget on the Dashboard.
 */

import { Plug, Loader2 } from 'lucide-react'
import { PluginCard } from './PluginCard'
import type { AccountPluginSummary, AvailablePlugin } from '../model/types'

interface PluginListProps {
  /** List of activated plugins */
  plugins: AccountPluginSummary[]
  /** List of available plugins (for showing unactivated ones) */
  availablePlugins?: AvailablePlugin[]
  /** Whether the data is loading */
  isLoading?: boolean
  /** Error message to display */
  error?: Error | null
  /** IDs of plugins currently being processed */
  pendingPluginIds?: Set<string>
  /** IDs of plugins whose pending operation is a secret rotation */
  rotatingPluginIds?: Set<string>
  /** Callback when activate button is clicked */
  onActivate?: (pluginId: string) => void
  /** Callback when deactivate button is clicked */
  onDeactivate?: (pluginId: string) => void
  /** Callback when the rotate-secret button is clicked */
  onRotateSecret?: (pluginId: string) => void
}

export function PluginList({
  plugins,
  availablePlugins = [],
  isLoading = false,
  error = null,
  pendingPluginIds = new Set(),
  rotatingPluginIds = new Set(),
  onActivate,
  onDeactivate,
  onRotateSecret,
}: PluginListProps) {
  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-8">
        <Loader2 className="h-8 w-8 animate-spin text-ink-muted" />
        <span className="ml-3 text-sm text-ink-secondary">Loading plugins...</span>
      </div>
    )
  }

  if (error) {
    return (
      <div className="rounded-lg border border-danger-border bg-danger-bg p-4 text-center">
        <p className="text-sm text-danger-text">
          Failed to load plugins: {error.message}
        </p>
      </div>
    )
  }

  // Merge activated plugins with available plugins
  const activatedPluginIds = new Set(plugins.map((p) => p.pluginId))

  // Get unactivated available plugins
  const unactivatedPlugins = availablePlugins.filter(
    (p) => !activatedPluginIds.has(p.pluginId) && p.isEnabled
  )

  const hasPlugins = plugins.length > 0 || unactivatedPlugins.length > 0

  if (!hasPlugins) {
    return (
      <div className="rounded-lg bg-white p-8 text-center shadow-panel">
        <Plug className="mx-auto h-12 w-12 text-ink-muted" />
        <p className="mt-4 text-sm text-ink-secondary">No plugins available</p>
        <p className="text-xs text-ink-muted">
          Plugins will appear here when they become available.
        </p>
      </div>
    )
  }

  return (
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {/* Activated plugins first */}
      {plugins.map((plugin) => (
        <PluginCard
          key={plugin.pluginId}
          plugin={plugin}
          isPending={pendingPluginIds.has(plugin.pluginId)}
          isRotating={rotatingPluginIds.has(plugin.pluginId)}
          onActivate={onActivate}
          onDeactivate={onDeactivate}
          onRotateSecret={onRotateSecret}
        />
      ))}
      {/* Then unactivated available plugins */}
      {unactivatedPlugins.map((plugin) => (
        <PluginCard
          key={plugin.pluginId}
          availablePlugin={plugin}
          isActivated={false}
          isPending={pendingPluginIds.has(plugin.pluginId)}
          onActivate={onActivate}
          onDeactivate={onDeactivate}
        />
      ))}
    </div>
  )
}

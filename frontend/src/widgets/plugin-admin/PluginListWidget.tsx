/**
 * PluginListWidget
 *
 * Container component for the plugin list section.
 */

import { PluginListView } from '@/features/plugin-admin/ui/PluginListView'
import { usePluginsQuery } from '@/features/plugin-admin/api/pluginQueries'

interface PluginListWidgetProps {
  onPluginClick?: (pluginId: string) => void
}

export function PluginListWidget({ onPluginClick }: PluginListWidgetProps) {
  const { data: plugins, isLoading, isError, error } = usePluginsQuery()

  if (isError) {
    return (
      <div className="rounded-lg border border-red-200 bg-red-50 p-4">
        <p className="text-sm font-medium text-red-800">
          {error instanceof Error ? error.message : 'Failed to load plugins'}
        </p>
      </div>
    )
  }

  return (
    <PluginListView
      plugins={plugins || []}
      isLoading={isLoading}
      onPluginClick={onPluginClick}
    />
  )
}

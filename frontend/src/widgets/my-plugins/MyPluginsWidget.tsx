/**
 * MyPluginsWidget Component
 *
 * Dashboard widget for managing user's plugin integrations.
 * Shows activated and available plugins with activation/deactivation actions.
 */

import { useState, useCallback } from 'react'
import { Plug } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/shared/ui/ui/card'
import {
  useAccountPluginsQuery,
  useAvailablePluginsQuery,
  useActivatePluginMutation,
  useDeactivatePluginMutation,
} from '@/features/my-plugins'
import { PluginList } from '@/features/my-plugins/ui/PluginList'
import { PluginActivationDialog } from '@/features/my-plugins/ui/PluginActivationDialog'
import type { AvailablePlugin, ActivatePluginRequest } from '@/features/my-plugins'

export function MyPluginsWidget() {
  // Queries
  const {
    data: accountPlugins,
    isLoading: isLoadingAccountPlugins,
    error: accountPluginsError,
  } = useAccountPluginsQuery(true) // Include inactive to show all

  const {
    data: availablePlugins,
    isLoading: isLoadingAvailablePlugins,
  } = useAvailablePluginsQuery()

  // Mutations
  const activatePluginMutation = useActivatePluginMutation()
  const deactivatePluginMutation = useDeactivatePluginMutation()

  // Dialog state
  const [dialogOpen, setDialogOpen] = useState(false)
  const [selectedPlugin, setSelectedPlugin] = useState<AvailablePlugin | null>(null)

  // Track pending plugin IDs
  const pendingPluginIds = new Set<string>()
  if (activatePluginMutation.isPending && activatePluginMutation.variables) {
    pendingPluginIds.add(activatePluginMutation.variables.pluginId)
  }
  if (deactivatePluginMutation.isPending && deactivatePluginMutation.variables) {
    pendingPluginIds.add(deactivatePluginMutation.variables)
  }

  // Handlers
  const handleActivateClick = useCallback((pluginId: string) => {
    const plugin = availablePlugins?.find((p) => p.pluginId === pluginId)
    if (plugin) {
      setSelectedPlugin(plugin)
      setDialogOpen(true)
    }
  }, [availablePlugins])

  const handleDeactivate = useCallback((pluginId: string) => {
    deactivatePluginMutation.mutate(pluginId)
  }, [deactivatePluginMutation])

  const handleDialogClose = useCallback(() => {
    setDialogOpen(false)
    setSelectedPlugin(null)
  }, [])

  const handleDialogSubmit = useCallback((pluginId: string, request: ActivatePluginRequest) => {
    activatePluginMutation.mutate(
      { pluginId, request },
      {
        onSuccess: () => {
          setDialogOpen(false)
          setSelectedPlugin(null)
        },
      }
    )
  }, [activatePluginMutation])

  const isLoading = isLoadingAccountPlugins || isLoadingAvailablePlugins

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Plug className="h-5 w-5" />
          My Plugins
        </CardTitle>
      </CardHeader>
      <CardContent>
        <PluginList
          plugins={accountPlugins?.content ?? []}
          availablePlugins={availablePlugins ?? []}
          isLoading={isLoading}
          error={accountPluginsError}
          pendingPluginIds={pendingPluginIds}
          onActivate={handleActivateClick}
          onDeactivate={handleDeactivate}
        />

        <PluginActivationDialog
          open={dialogOpen}
          plugin={selectedPlugin}
          onClose={handleDialogClose}
          onSubmit={handleDialogSubmit}
          isLoading={activatePluginMutation.isPending}
        />
      </CardContent>
    </Card>
  )
}

/**
 * MyPluginsWidget Component
 *
 * Dashboard widget for managing user's plugin integrations.
 * Shows activated and available plugins with activation/deactivation actions.
 * Includes Logs tab for viewing plugin activity.
 */

import { useState, useCallback, useMemo } from 'react'
import { Plug, FileText } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/shared/ui/ui/card'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/shared/ui/ui/tabs'
import {
  useAccountPluginsQuery,
  useAvailablePluginsQuery,
  useActivatePluginMutation,
  useDeactivatePluginMutation,
} from '@/features/my-plugins'
import { PluginList } from '@/features/my-plugins/ui/PluginList'
import { PluginLogsTab } from '@/features/my-plugins/ui/PluginLogsTab'
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

  // Track pending plugin IDs with useMemo to prevent unnecessary re-renders
  const pendingPluginIds = useMemo(() => {
    const ids = new Set<string>()
    if (activatePluginMutation.isPending && activatePluginMutation.variables) {
      ids.add(activatePluginMutation.variables.pluginId)
    }
    if (deactivatePluginMutation.isPending && deactivatePluginMutation.variables) {
      ids.add(deactivatePluginMutation.variables)
    }
    return ids
  }, [
    activatePluginMutation.isPending,
    activatePluginMutation.variables,
    deactivatePluginMutation.isPending,
    deactivatePluginMutation.variables,
  ])

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

  // Get the first active plugin for logs (default to bit-bi if not found)
  const activePluginId = accountPlugins?.content?.find(p => p.isActive)?.pluginId ?? 'bit-bi'

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Plug className="h-5 w-5" />
          My Plugins
        </CardTitle>
      </CardHeader>
      <CardContent>
        <Tabs defaultValue="plugins" className="w-full">
          <TabsList className="mb-4">
            <TabsTrigger value="plugins" className="flex items-center gap-1">
              <Plug className="h-4 w-4" />
              Plugins
            </TabsTrigger>
            <TabsTrigger value="logs" className="flex items-center gap-1">
              <FileText className="h-4 w-4" />
              Logs
            </TabsTrigger>
          </TabsList>

          <TabsContent value="plugins">
            <PluginList
              plugins={accountPlugins?.content ?? []}
              availablePlugins={availablePlugins ?? []}
              isLoading={isLoading}
              error={accountPluginsError}
              pendingPluginIds={pendingPluginIds}
              onActivate={handleActivateClick}
              onDeactivate={handleDeactivate}
            />
          </TabsContent>

          <TabsContent value="logs">
            <PluginLogsTab pluginId={activePluginId} />
          </TabsContent>
        </Tabs>

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

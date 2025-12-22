/**
 * TanStack Query hooks for plugin activation/deactivation.
 *
 * @module features/my-plugins/api/myPluginsMutations
 */

import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { pluginKeys } from '@/entities/plugin/api/keys'
import { activatePlugin, deactivatePlugin } from './myPluginsApi'
import type { ActivatePluginRequest, PluginActivationResponse } from '../model/types'

/**
 * Hook to activate a plugin for the current account.
 * Automatically invalidates account plugins queries on success.
 */
export function useActivatePluginMutation() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({
      pluginId,
      request,
    }: {
      pluginId: string
      request: ActivatePluginRequest
    }) => activatePlugin(pluginId, request),
    onSuccess: (data: PluginActivationResponse) => {
      // Invalidate all account plugin queries to refetch fresh data
      queryClient.invalidateQueries({ queryKey: pluginKeys.accountPlugins() })
      // Show success toast
      toast.success('Plugin activated', {
        description: `${data.pluginName} has been activated successfully.`,
      })
    },
    onError: (error: Error) => {
      // Show error toast
      toast.error('Failed to activate plugin', {
        description: error.message,
      })
    },
  })
}

/**
 * Hook to deactivate a plugin for the current account.
 * Automatically invalidates account plugins queries on success.
 */
export function useDeactivatePluginMutation() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (pluginId: string) => deactivatePlugin(pluginId),
    onSuccess: (_, pluginId) => {
      // Invalidate all account plugin queries to refetch fresh data
      queryClient.invalidateQueries({ queryKey: pluginKeys.accountPlugins() })
      // Show success toast
      toast.success('Plugin deactivated', {
        description: `Plugin ${pluginId} has been deactivated successfully.`,
      })
    },
    onError: (error: Error) => {
      // Show error toast
      toast.error('Failed to deactivate plugin', {
        description: error.message,
      })
    },
  })
}

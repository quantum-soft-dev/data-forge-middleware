/**
 * TanStack Query hooks for plugin activation/deactivation.
 *
 * @module features/my-plugins/api/myPluginsMutations
 */

import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { pluginKeys } from '@/entities/plugin/api/keys'
import { activatePlugin, deactivatePlugin, rotatePluginSecret } from './myPluginsApi'
import { parsePluginSecret } from '../model/pluginSecret'
import type { PluginSecret } from '../model/pluginSecret'
import type { ActivatePluginRequest, PluginActivationResponse } from '../model/types'

interface ActivatePluginMutationOptions {
  /**
   * Called with the freshly minted secret when the activation issued one.
   * The secret is handed straight to the caller and never cached or toasted —
   * the backend keeps only a hash, so this is the single chance to show it.
   */
  onSecretRevealed?: (secret: PluginSecret) => void
}

/**
 * Hook to activate a plugin for the current account.
 * Automatically invalidates account plugins queries on success.
 */
export function useActivatePluginMutation(options: ActivatePluginMutationOptions = {}) {
  const queryClient = useQueryClient()
  const { onSecretRevealed } = options

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
      // Show success toast — deliberately without the secret
      toast.success('Plugin activated', {
        description: `${data.pluginName} has been activated successfully.`,
      })

      const secret = parsePluginSecret(data.pluginId, data.apiKey)
      if (secret) {
        onSecretRevealed?.(secret)
      }
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

interface RotatePluginSecretMutationOptions {
  /** Called with the reissued secret — the single chance to show it. */
  onSecretRevealed?: (secret: PluginSecret) => void
}

/**
 * Hook to reissue a plugin's secret for the current account.
 *
 * The previous secret stops authenticating immediately, so the new value is
 * handed straight to the caller and never cached or toasted.
 */
export function useRotatePluginSecretMutation(
  options: RotatePluginSecretMutationOptions = {}
) {
  const { onSecretRevealed } = options

  return useMutation({
    mutationFn: (pluginId: string) => rotatePluginSecret(pluginId),
    onSuccess: (secret: PluginSecret) => {
      // Deliberately without the secret itself
      toast.success('Secret rotated', {
        description: 'The previous value has stopped working.',
      })
      onSecretRevealed?.(secret)
    },
    onError: (error: Error) => {
      toast.error('Failed to rotate secret', {
        description: error.message,
      })
    },
  })
}

/**
 * Tests for the plugin secret rotation mutation.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ReactNode } from 'react'
import { useRotatePluginSecretMutation } from '../myPluginsMutations'
import * as api from '../myPluginsApi'
import type { PluginSecret } from '../../model/pluginSecret'

vi.mock('../myPluginsApi', () => ({
  activatePlugin: vi.fn(),
  deactivatePlugin: vi.fn(),
  rotatePluginSecret: vi.fn(),
}))

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

import { toast } from 'sonner'

describe('useRotatePluginSecretMutation', () => {
  let queryClient: QueryClient

  const rotatedSecret: PluginSecret = {
    kind: 'api-key',
    pluginId: 'bit-bi',
    value: 'plk_rotated_secret',
  }

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    vi.clearAllMocks()
  })

  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )

  it('should hand the rotated secret to the caller', async () => {
    vi.mocked(api.rotatePluginSecret).mockResolvedValue(rotatedSecret)
    const onSecretRevealed = vi.fn()

    const { result } = renderHook(() => useRotatePluginSecretMutation({ onSecretRevealed }), {
      wrapper,
    })

    act(() => {
      result.current.mutate('bit-bi')
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(api.rotatePluginSecret).toHaveBeenCalledWith('bit-bi')
    expect(onSecretRevealed).toHaveBeenCalledWith(rotatedSecret)
  })

  it('should keep the rotated secret out of the toast', async () => {
    vi.mocked(api.rotatePluginSecret).mockResolvedValue(rotatedSecret)

    const { result } = renderHook(() => useRotatePluginSecretMutation(), { wrapper })

    act(() => {
      result.current.mutate('bit-bi')
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(toast.success).toHaveBeenCalled()
    expect(JSON.stringify(vi.mocked(toast.success).mock.calls)).not.toContain('plk_rotated_secret')
  })

  it('should show an error toast when rotation fails', async () => {
    vi.mocked(api.rotatePluginSecret).mockRejectedValue(new Error('Plugin not activated'))

    const { result } = renderHook(() => useRotatePluginSecretMutation(), { wrapper })

    act(() => {
      result.current.mutate('bit-bi')
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(toast.error).toHaveBeenCalledWith('Failed to rotate secret', {
      description: 'Plugin not activated',
    })
  })
})

/**
 * Tests for my-plugins mutation hooks
 *
 * Tests plugin activation and deactivation mutations.
 *
 * Feature: My Plugins Dashboard Widget
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ReactNode } from 'react'
import { useActivatePluginMutation, useDeactivatePluginMutation } from '../myPluginsMutations'
import * as api from '../myPluginsApi'
import type { PluginActivationResponse } from '../../model/types'

// Mock the API
vi.mock('../myPluginsApi', () => ({
  activatePlugin: vi.fn(),
  deactivatePlugin: vi.fn(),
}))

// Mock sonner toast
vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}))

import { toast } from 'sonner'

describe('myPluginsMutations', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    })
    vi.clearAllMocks()
  })

  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )

  describe('useActivatePluginMutation', () => {
    const mockResponse: PluginActivationResponse = {
      pluginId: 'bit-bi',
      pluginName: 'Bit BI',
      accountId: 'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
      isActive: true,
      activatedAt: '2025-12-22T10:00:00Z',
      lastUsedAt: null,
    }

    it('should activate plugin with POST request', async () => {
      vi.mocked(api.activatePlugin).mockResolvedValue(mockResponse)

      const { result } = renderHook(() => useActivatePluginMutation(), { wrapper })

      act(() => {
        result.current.mutate({
          pluginId: 'bit-bi',
          request: { pluginData: { tenantId: 'test-tenant' } },
        })
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      expect(api.activatePlugin).toHaveBeenCalledWith('bit-bi', {
        pluginData: { tenantId: 'test-tenant' },
      })
    })

    it('should invalidate query cache on success', async () => {
      vi.mocked(api.activatePlugin).mockResolvedValue(mockResponse)

      // Set up some cached data
      queryClient.setQueryData(['plugins', 'account'], { content: [] })

      const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

      const { result } = renderHook(() => useActivatePluginMutation(), { wrapper })

      act(() => {
        result.current.mutate({
          pluginId: 'bit-bi',
          request: { pluginData: { tenantId: 'test-tenant' } },
        })
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      expect(invalidateSpy).toHaveBeenCalledWith({
        queryKey: ['plugins', 'account'],
      })
    })

    it('should show success toast on activation', async () => {
      vi.mocked(api.activatePlugin).mockResolvedValue(mockResponse)

      const { result } = renderHook(() => useActivatePluginMutation(), { wrapper })

      act(() => {
        result.current.mutate({
          pluginId: 'bit-bi',
          request: { pluginData: { tenantId: 'test-tenant' } },
        })
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      expect(toast.success).toHaveBeenCalledWith('Plugin activated', {
        description: 'Bit BI has been activated successfully.',
      })
    })

    it('should hand the revealed secret to the caller when the response carries one', async () => {
      vi.mocked(api.activatePlugin).mockResolvedValue({ ...mockResponse, apiKey: 'plk_secret123' })
      const onSecretRevealed = vi.fn()

      const { result } = renderHook(() => useActivatePluginMutation({ onSecretRevealed }), {
        wrapper,
      })

      act(() => {
        result.current.mutate({
          pluginId: 'bit-bi',
          request: { pluginData: { tenantId: 'test-tenant' } },
        })
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      expect(onSecretRevealed).toHaveBeenCalledWith({
        kind: 'api-key',
        pluginId: 'bit-bi',
        value: 'plk_secret123',
      })
    })

    it('should split parquet-export credentials before handing them over', async () => {
      vi.mocked(api.activatePlugin).mockResolvedValue({
        ...mockResponse,
        pluginId: 'parquet-export',
        pluginName: 'Parquet Export',
        apiKey: 'pex_login12345:pw123456',
      })
      const onSecretRevealed = vi.fn()

      const { result } = renderHook(() => useActivatePluginMutation({ onSecretRevealed }), {
        wrapper,
      })

      act(() => {
        result.current.mutate({ pluginId: 'parquet-export', request: { pluginData: {} } })
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      expect(onSecretRevealed).toHaveBeenCalledWith({
        kind: 'basic-auth',
        pluginId: 'parquet-export',
        login: 'pex_login12345',
        password: 'pw123456',
      })
    })

    it('should not hand over a secret when the response carries none', async () => {
      vi.mocked(api.activatePlugin).mockResolvedValue(mockResponse)
      const onSecretRevealed = vi.fn()

      const { result } = renderHook(() => useActivatePluginMutation({ onSecretRevealed }), {
        wrapper,
      })

      act(() => {
        result.current.mutate({
          pluginId: 'bit-bi',
          request: { pluginData: { tenantId: 'test-tenant' } },
        })
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      expect(onSecretRevealed).not.toHaveBeenCalled()
    })

    it('should keep the secret out of the toast', async () => {
      vi.mocked(api.activatePlugin).mockResolvedValue({ ...mockResponse, apiKey: 'plk_secret123' })

      const { result } = renderHook(() => useActivatePluginMutation(), { wrapper })

      act(() => {
        result.current.mutate({
          pluginId: 'bit-bi',
          request: { pluginData: { tenantId: 'test-tenant' } },
        })
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      expect(JSON.stringify(vi.mocked(toast.success).mock.calls)).not.toContain('plk_secret123')
    })

    it('should show error toast on failure', async () => {
      const mockError = new Error('Validation failed: tenantId is required')
      vi.mocked(api.activatePlugin).mockRejectedValue(mockError)

      const { result } = renderHook(() => useActivatePluginMutation(), { wrapper })

      act(() => {
        result.current.mutate({
          pluginId: 'bit-bi',
          request: { pluginData: {} },
        })
      })

      await waitFor(() => expect(result.current.isError).toBe(true))

      expect(toast.error).toHaveBeenCalledWith('Failed to activate plugin', {
        description: 'Validation failed: tenantId is required',
      })
    })
  })

  describe('useDeactivatePluginMutation', () => {
    it('should deactivate plugin with DELETE request', async () => {
      vi.mocked(api.deactivatePlugin).mockResolvedValue(undefined)

      const { result } = renderHook(() => useDeactivatePluginMutation(), { wrapper })

      act(() => {
        result.current.mutate('bit-bi')
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      expect(api.deactivatePlugin).toHaveBeenCalledWith('bit-bi')
    })

    it('should invalidate query cache on success', async () => {
      vi.mocked(api.deactivatePlugin).mockResolvedValue(undefined)

      const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

      const { result } = renderHook(() => useDeactivatePluginMutation(), { wrapper })

      act(() => {
        result.current.mutate('bit-bi')
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      expect(invalidateSpy).toHaveBeenCalledWith({
        queryKey: ['plugins', 'account'],
      })
    })

    it('should show success toast on deactivation', async () => {
      vi.mocked(api.deactivatePlugin).mockResolvedValue(undefined)

      const { result } = renderHook(() => useDeactivatePluginMutation(), { wrapper })

      act(() => {
        result.current.mutate('bit-bi')
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      expect(toast.success).toHaveBeenCalledWith('Plugin deactivated', {
        description: 'Plugin bit-bi has been deactivated successfully.',
      })
    })

    it('should show error toast on failure', async () => {
      const mockError = new Error('Plugin not found')
      vi.mocked(api.deactivatePlugin).mockRejectedValue(mockError)

      const { result } = renderHook(() => useDeactivatePluginMutation(), { wrapper })

      act(() => {
        result.current.mutate('unknown-plugin')
      })

      await waitFor(() => expect(result.current.isError).toBe(true))

      expect(toast.error).toHaveBeenCalledWith('Failed to deactivate plugin', {
        description: 'Plugin not found',
      })
    })
  })
})

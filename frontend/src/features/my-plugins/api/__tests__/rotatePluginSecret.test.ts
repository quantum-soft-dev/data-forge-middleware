/**
 * Tests for plugin secret rotation.
 *
 * Rotation is the only way back to a working integration once the one-shot
 * activation secret is lost, so each plugin's endpoint and response shape has
 * to be mapped correctly.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { apiClient } from '@/shared/api/client'
import { rotatePluginSecret, supportsSecretRotation } from '../myPluginsApi'

vi.mock('@/shared/api/client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), delete: vi.fn() },
}))

const mockedPost = vi.mocked(apiClient.post)

beforeEach(() => {
  vi.clearAllMocks()
})

describe('supportsSecretRotation', () => {
  it.each([
    ['bit-bi', true],
    ['parquet-export', true],
    ['analytics', false],
  ])('should report %s as %s', (pluginId, expected) => {
    expect(supportsSecretRotation(pluginId)).toBe(expected)
  })
})

describe('rotatePluginSecret', () => {
  it('should rotate the bit-bi api key and return it as a secret', async () => {
    mockedPost.mockResolvedValue({ data: { apiKey: 'plk_rotated' } } as never)

    const secret = await rotatePluginSecret('bit-bi')

    expect(mockedPost).toHaveBeenCalledWith('/v1/account/plugins/bit-bi/rotate-api-key')
    expect(secret).toEqual({ kind: 'api-key', pluginId: 'bit-bi', value: 'plk_rotated' })
  })

  it('should rotate the parquet-export password and keep login and password apart', async () => {
    mockedPost.mockResolvedValue({ data: { login: 'pex_login12345', password: 'pw_rotated' } } as never)

    const secret = await rotatePluginSecret('parquet-export')

    expect(mockedPost).toHaveBeenCalledWith('/v1/account/plugins/parquet-export/rotate-password')
    expect(secret).toEqual({
      kind: 'basic-auth',
      pluginId: 'parquet-export',
      login: 'pex_login12345',
      password: 'pw_rotated',
    })
  })

  it('should reject a plugin that has no rotatable secret', async () => {
    await expect(rotatePluginSecret('analytics')).rejects.toThrow(/analytics/)
    expect(mockedPost).not.toHaveBeenCalled()
  })
})

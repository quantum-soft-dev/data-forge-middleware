/**
 * Tests for the one-shot plugin secret model.
 *
 * The activation response carries the secret in a single `apiKey` field whose
 * meaning depends on the plugin: a raw key for bit-bi, `login:password` Basic
 * Auth credentials for parquet-export.
 */

import { describe, it, expect } from 'vitest'
import { parsePluginSecret } from '../pluginSecret'

describe('parsePluginSecret', () => {
  it('should return an api-key secret for bit-bi', () => {
    expect(parsePluginSecret('bit-bi', 'plk_abc123')).toEqual({
      kind: 'api-key',
      pluginId: 'bit-bi',
      value: 'plk_abc123',
    })
  })

  it('should split parquet-export credentials into login and password', () => {
    expect(parsePluginSecret('parquet-export', 'pex_Ab3xY9Qm2Lk4:Kf82secret')).toEqual({
      kind: 'basic-auth',
      pluginId: 'parquet-export',
      login: 'pex_Ab3xY9Qm2Lk4',
      password: 'Kf82secret',
    })
  })

  it('should split parquet-export credentials on the first colon only', () => {
    const secret = parsePluginSecret('parquet-export', 'pex_login:pass:word')

    expect(secret).toEqual({
      kind: 'basic-auth',
      pluginId: 'parquet-export',
      login: 'pex_login',
      password: 'pass:word',
    })
  })

  it('should fall back to an api-key secret when parquet-export sends no colon', () => {
    expect(parsePluginSecret('parquet-export', 'unexpected-shape')).toEqual({
      kind: 'api-key',
      pluginId: 'parquet-export',
      value: 'unexpected-shape',
    })
  })

  it('should fall back to an api-key secret when a credentials half is empty', () => {
    expect(parsePluginSecret('parquet-export', 'pex_login:')).toEqual({
      kind: 'api-key',
      pluginId: 'parquet-export',
      value: 'pex_login:',
    })
  })

  it.each([
    ['null', null],
    ['undefined', undefined],
    ['an empty string', ''],
    ['blank space', '   '],
  ])('should return null for %s', (_label, raw) => {
    expect(parsePluginSecret('bit-bi', raw)).toBeNull()
  })
})

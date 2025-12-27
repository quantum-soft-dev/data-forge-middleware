/**
 * React Query Key Factory for Plugins
 *
 * Provides consistent query keys for caching and invalidation.
 * Follows TanStack Query best practices for key management.
 */

import type { AuditLogFilters } from '../model/types'

export const pluginKeys = {
  all: ['plugins'] as const,
  lists: () => [...pluginKeys.all, 'list'] as const,
  list: () => [...pluginKeys.lists()] as const,
  auditLogs: () => [...pluginKeys.all, 'audit'] as const,
  auditLogList: (filters: AuditLogFilters) =>
    [...pluginKeys.auditLogs(), filters] as const,
  auditLogByPlugin: (pluginId: string, page: number, size: number) =>
    [...pluginKeys.auditLogs(), 'plugin', pluginId, { page, size }] as const,
  // Account plugins (user-facing)
  accountPlugins: () => [...pluginKeys.all, 'account'] as const,
  accountPluginList: (includeInactive: boolean) =>
    [...pluginKeys.accountPlugins(), { includeInactive }] as const,
  // Plugin logs (user-facing)
  pluginLogs: (pluginId: string) => [...pluginKeys.all, 'logs', pluginId] as const,
  pluginLogsList: (pluginId: string, page: number) =>
    [...pluginKeys.pluginLogs(pluginId), { page }] as const,
}

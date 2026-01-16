/**
 * React Query Key Factory for Plugins
 *
 * Provides consistent query keys for caching and invalidation.
 * Follows TanStack Query best practices for key management.
 */

import type { AuditLogFilters } from '../model/types'

/**
 * Filter options for plugin logs query.
 */
interface PluginLogQueryFilters {
  page?: number
  size?: number
  siteId?: string
  from?: string
  to?: string
}

/**
 * Filter options for batch SQL query.
 */
interface BatchSqlQueryFilters {
  page?: number
  size?: number
  siteId?: string
}

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
  pluginLogsList: (pluginId: string, filters: PluginLogQueryFilters) =>
    [...pluginKeys.pluginLogs(pluginId), filters] as const,
  // Batch SQL status (user-facing)
  batchSql: (pluginId: string) => [...pluginKeys.all, 'batchSql', pluginId] as const,
  batchSqlList: (pluginId: string, filters: BatchSqlQueryFilters) =>
    [...pluginKeys.batchSql(pluginId), 'list', filters] as const,
  // SQL generations (user-facing)
  userGenerations: (pluginId: string) =>
    [...pluginKeys.all, 'userGenerations', pluginId] as const,
  userGenerationsList: (pluginId: string, page: number, includeSuperseded: boolean) =>
    [...pluginKeys.userGenerations(pluginId), 'list', { page, includeSuperseded }] as const,
  // SQL content (user-facing)
  userSqlContent: (pluginId: string, generationId: string) =>
    [...pluginKeys.all, 'userSqlContent', pluginId, generationId] as const,
  userSqlContentPage: (pluginId: string, generationId: string, page: number) =>
    [...pluginKeys.userSqlContent(pluginId, generationId), { page }] as const,
}

/**
 * AuditLogTable Component
 *
 * Table display for plugin audit logs.
 */

import { format } from 'date-fns'
import { CheckCircle, XCircle, Clock } from 'lucide-react'
import { monitoringTokens, severityTokens } from '@/shared/ui/tokens'
import type { PluginAuditLogEntry } from '@/entities/plugin/model/types'
import { ActionTypeBadge } from '@/entities/plugin/ui/ActionTypeBadge'
import { Pagination } from '@/widgets/account-table/Pagination'

interface AuditLogTableProps {
  logs: PluginAuditLogEntry[]
  totalCount: number
  page: number // 1-indexed for UI
  pageSize: number
  totalPages: number
  onPageChange: (page: number) => void
  onPageSizeChange: (size: number) => void
  isLoading?: boolean
}

export function AuditLogTable({
  logs,
  totalCount,
  page,
  pageSize,
  totalPages,
  onPageChange,
  onPageSizeChange,
  isLoading = false,
}: AuditLogTableProps) {
  if (isLoading) {
    return (
      <div className="rounded-lg bg-white p-8 shadow-card">
        <div className="flex items-center justify-center">
          <div className="h-8 w-8 animate-spin rounded-full border-4 border-secondary border-t-brand" />
          <span className="ml-3 text-sm text-ink-secondary">Loading audit logs...</span>
        </div>
      </div>
    )
  }

  if (logs.length === 0) {
    return (
      <div className="rounded-lg bg-white p-8 text-center shadow-card">
        <p className="text-sm text-ink-secondary">No audit logs found</p>
      </div>
    )
  }

  return (
    <div className="overflow-hidden rounded-lg bg-white shadow-card">
      <div className="overflow-x-auto">
        <table className="min-w-full divide-y divide-separator">
          <thead className="bg-white">
            <tr>
              <th className="px-4 py-3 text-left text-xs font-medium text-ink-secondary">
                Time
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium text-ink-secondary">
                Plugin
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium text-ink-secondary">
                Action
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium text-ink-secondary">
                Status
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium text-ink-secondary">
                Account
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium text-ink-secondary">
                Duration
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium text-ink-secondary">
                HTTP
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium text-ink-secondary">
                Error
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium text-ink-secondary">
                IP
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-separator bg-white">
            {logs.map((log) => (
              <tr key={log.id} className="hover:bg-surface-hover">
                <td className="whitespace-nowrap px-4 py-3">
                  <div className="text-sm">
                    <div className="font-medium text-ink">
                      {format(new Date(log.occurredAt), 'MMM d, yyyy')}
                    </div>
                    <div className="text-ink-secondary">
                      {format(new Date(log.occurredAt), 'HH:mm:ss')}
                    </div>
                  </div>
                </td>
                <td className="whitespace-nowrap px-4 py-3">
                  <div className="text-sm font-medium text-ink">{log.pluginId}</div>
                </td>
                <td className="whitespace-nowrap px-4 py-3">
                  <ActionTypeBadge actionType={log.actionType} />
                </td>
                <td className="whitespace-nowrap px-4 py-3">
                  <span
                    className="inline-flex items-center gap-1"
                    style={{ color: log.success ? severityTokens.healthy.text : severityTokens.critical.text }}
                  >
                    {log.success ? (
                      <>
                        <CheckCircle className="h-4 w-4" />
                        <span className="text-sm">Success</span>
                      </>
                    ) : (
                      <>
                        <XCircle className="h-4 w-4" />
                        <span className="text-sm">Failed</span>
                      </>
                    )}
                  </span>
                </td>
                <td className="whitespace-nowrap px-4 py-3">
                  {log.accountId ? (
                    <span className="font-mono text-sm text-ink-secondary">
                      {log.accountId.slice(0, 8)}...
                    </span>
                  ) : (
                    <span className="text-ink-muted">-</span>
                  )}
                </td>
                <td className="whitespace-nowrap px-4 py-3">
                  {log.durationMs !== null ? (
                    <span className="inline-flex items-center gap-1 text-sm text-ink-secondary">
                      <Clock className="h-3.5 w-3.5" />
                      {log.durationMs}ms
                    </span>
                  ) : (
                    <span className="text-ink-muted">-</span>
                  )}
                </td>
                <td className="whitespace-nowrap px-4 py-3">
                  {log.responseStatus !== null ? (
                    <span
                      className="rounded-full px-2 py-0.5 text-xs font-medium tabular-nums"
                      style={
                        log.responseStatus >= 200 && log.responseStatus < 300
                          ? { background: severityTokens.healthy.bg, color: severityTokens.healthy.text }
                          : log.responseStatus >= 400
                            ? { background: severityTokens.critical.bg, color: severityTokens.critical.text }
                            : { background: monitoringTokens.subtleBg, color: monitoringTokens.textSecondary }
                      }
                    >
                      {log.responseStatus}
                    </span>
                  ) : (
                    <span className="text-ink-muted">-</span>
                  )}
                </td>
                <td className="max-w-xs truncate px-4 py-3">
                  {log.errorMessage ? (
                    <span className="text-sm text-danger-text" title={log.errorMessage}>
                      {log.errorMessage}
                    </span>
                  ) : (
                    <span className="text-ink-muted">-</span>
                  )}
                </td>
                <td className="whitespace-nowrap px-4 py-3">
                  {log.ipAddress ? (
                    <span className="font-mono text-sm text-ink-secondary">{log.ipAddress}</span>
                  ) : (
                    <span className="text-ink-muted">-</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <Pagination
        page={page}
        totalPages={totalPages}
        pageSize={pageSize}
        totalElements={totalCount}
        onPageChange={onPageChange}
        onPageSizeChange={onPageSizeChange}
      />
    </div>
  )
}

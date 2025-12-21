/**
 * AuditLogTable Component
 *
 * Table display for plugin audit logs.
 */

import { format } from 'date-fns'
import { CheckCircle, XCircle, Clock } from 'lucide-react'
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
      <div className="rounded-lg border border-gray-200 bg-white p-8">
        <div className="flex items-center justify-center">
          <div className="h-8 w-8 animate-spin rounded-full border-4 border-gray-200 border-t-blue-600" />
          <span className="ml-3 text-sm text-gray-600">Loading audit logs...</span>
        </div>
      </div>
    )
  }

  if (logs.length === 0) {
    return (
      <div className="rounded-lg border border-gray-200 bg-white p-8 text-center">
        <p className="text-sm text-gray-500">No audit logs found</p>
      </div>
    )
  }

  return (
    <div className="overflow-hidden rounded-lg border border-gray-200 bg-white shadow-sm">
      <div className="overflow-x-auto">
        <table className="min-w-full divide-y divide-gray-200">
          <thead className="bg-gray-50">
            <tr>
              <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider text-gray-500">
                Time
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider text-gray-500">
                Plugin
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider text-gray-500">
                Action
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider text-gray-500">
                Status
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider text-gray-500">
                Account
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider text-gray-500">
                Duration
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider text-gray-500">
                HTTP
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider text-gray-500">
                Error
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider text-gray-500">
                IP
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-200 bg-white">
            {logs.map((log) => (
              <tr key={log.id} className="hover:bg-gray-50">
                <td className="whitespace-nowrap px-4 py-3">
                  <div className="text-sm">
                    <div className="font-medium text-gray-900">
                      {format(new Date(log.occurredAt), 'MMM d, yyyy')}
                    </div>
                    <div className="text-gray-500">
                      {format(new Date(log.occurredAt), 'HH:mm:ss')}
                    </div>
                  </div>
                </td>
                <td className="whitespace-nowrap px-4 py-3">
                  <div className="text-sm font-medium text-gray-900">{log.pluginId}</div>
                </td>
                <td className="whitespace-nowrap px-4 py-3">
                  <ActionTypeBadge actionType={log.actionType} />
                </td>
                <td className="whitespace-nowrap px-4 py-3">
                  <span
                    className={`inline-flex items-center gap-1 ${
                      log.success ? 'text-green-600' : 'text-red-600'
                    }`}
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
                    <span className="font-mono text-sm text-gray-600">
                      {log.accountId.slice(0, 8)}...
                    </span>
                  ) : (
                    <span className="text-gray-400">-</span>
                  )}
                </td>
                <td className="whitespace-nowrap px-4 py-3">
                  {log.durationMs !== null ? (
                    <span className="inline-flex items-center gap-1 text-sm text-gray-600">
                      <Clock className="h-3.5 w-3.5" />
                      {log.durationMs}ms
                    </span>
                  ) : (
                    <span className="text-gray-400">-</span>
                  )}
                </td>
                <td className="whitespace-nowrap px-4 py-3">
                  {log.responseStatus !== null ? (
                    <span
                      className={`rounded px-2 py-0.5 text-xs font-medium ${
                        log.responseStatus >= 200 && log.responseStatus < 300
                          ? 'bg-green-100 text-green-800'
                          : log.responseStatus >= 400
                            ? 'bg-red-100 text-red-800'
                            : 'bg-gray-100 text-gray-800'
                      }`}
                    >
                      {log.responseStatus}
                    </span>
                  ) : (
                    <span className="text-gray-400">-</span>
                  )}
                </td>
                <td className="max-w-xs truncate px-4 py-3">
                  {log.errorMessage ? (
                    <span className="text-sm text-red-600" title={log.errorMessage}>
                      {log.errorMessage}
                    </span>
                  ) : (
                    <span className="text-gray-400">-</span>
                  )}
                </td>
                <td className="whitespace-nowrap px-4 py-3">
                  {log.ipAddress ? (
                    <span className="font-mono text-sm text-gray-600">{log.ipAddress}</span>
                  ) : (
                    <span className="text-gray-400">-</span>
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

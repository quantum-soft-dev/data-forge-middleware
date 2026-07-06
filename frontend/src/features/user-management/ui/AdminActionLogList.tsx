/**
 * AdminActionLogList Component
 *
 * Per T065: Displays paginated audit trail of administrative actions
 * performed on an account (create, lock, unlock, reset password).
 *
 * Features:
 * - Paginated list with page controls
 * - Color-coded status badges (SUCCESS=green, FAILED=red)
 * - Action type badges with icons
 * - Formatted timestamps
 * - Empty state when no logs
 * - Loading state with skeleton
 */

import { useState } from 'react'
import { useAccountAuditLogsQuery } from '../api/userQueries'
import type { AdminActionLog } from '@/entities/account/model/types'
import {
  UserPlus,
  Lock,
  Unlock,
  KeyRound,
  CheckCircle,
  XCircle,
  ChevronLeft,
  ChevronRight
} from 'lucide-react'

interface AdminActionLogListProps {
  accountId: string
}

export function AdminActionLogList({ accountId }: AdminActionLogListProps) {
  const [page, setPage] = useState(0)
  const pageSize = 10

  const { data, isLoading, isError, error } = useAccountAuditLogsQuery(accountId, page, pageSize)

  // Action type icons and labels
  const getActionInfo = (actionType: AdminActionLog['actionType']) => {
    switch (actionType) {
      case 'CREATE_ACCOUNT':
        return { icon: UserPlus, label: 'Created Account', color: 'text-blue-600 bg-blue-50' }
      case 'LOCK_ACCOUNT':
        return { icon: Lock, label: 'Locked Account', color: 'text-orange-600 bg-orange-50' }
      case 'UNLOCK_ACCOUNT':
        return { icon: Unlock, label: 'Unlocked Account', color: 'text-green-600 bg-green-50' }
      case 'RESET_PASSWORD':
        return { icon: KeyRound, label: 'Reset Password', color: 'text-brand bg-brand-50' }
      default:
        return { icon: UserPlus, label: actionType, color: 'text-ink-secondary bg-surface-subtle' }
    }
  }

  // Status badge styling
  const getStatusBadge = (status: AdminActionLog['status']) => {
    if (status === 'SUCCESS') {
      return (
        <span className="inline-flex items-center gap-1 rounded-full bg-green-100 px-2 py-1 text-xs font-medium text-green-700">
          <CheckCircle className="h-3 w-3" />
          Success
        </span>
      )
    }
    return (
      <span className="inline-flex items-center gap-1 rounded-full bg-red-100 px-2 py-1 text-xs font-medium text-red-700">
        <XCircle className="h-3 w-3" />
        Failed
      </span>
    )
  }

  // Format timestamp
  const formatTimestamp = (timestamp: string) => {
    const date = new Date(timestamp)
    return new Intl.DateTimeFormat('en-US', {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    }).format(date)
  }

  // Loading skeleton
  if (isLoading) {
    return (
      <div className="space-y-3">
        {[...Array(5)].map((_, i) => (
          <div key={i} className="animate-pulse rounded-lg bg-white shadow-card p-4">
            <div className="flex items-start justify-between">
              <div className="flex-1 space-y-2">
                <div className="h-4 w-32 rounded bg-gray-200" />
                <div className="h-3 w-48 rounded bg-surface-subtle" />
              </div>
              <div className="h-6 w-20 rounded-full bg-gray-200" />
            </div>
          </div>
        ))}
      </div>
    )
  }

  // Error state
  if (isError) {
    return (
      <div className="rounded-lg border border-red-200 bg-red-50 p-4">
        <p className="text-sm font-medium text-red-800">Failed to load audit logs</p>
        <p className="mt-1 text-xs text-red-700">
          {error instanceof Error ? error.message : 'Unknown error'}
        </p>
      </div>
    )
  }

  // Empty state
  if (!data || data.content.length === 0) {
    return (
      <div className="rounded-lg bg-white p-8 text-center shadow-card">
        <UserPlus className="mx-auto h-12 w-12 text-ink-muted" />
        <p className="mt-4 text-sm font-medium text-ink-secondary">No audit logs yet</p>
        <p className="mt-1 text-xs text-ink-secondary">
          Administrative actions will appear here
        </p>
      </div>
    )
  }

  const { content: logs, totalPages, totalElements } = data
  const showPagination = totalPages > 1

  return (
    <div className="space-y-4">
      {/* Audit log entries */}
      <div className="space-y-3">
        {logs.map((log) => {
          const actionInfo = getActionInfo(log.actionType)
          const ActionIcon = actionInfo.icon

          return (
            <div
              key={log.id}
              className="rounded-lg bg-white shadow-card p-4 transition-shadow hover:shadow-md"
            >
              <div className="flex items-start justify-between">
                <div className="flex items-start gap-3">
                  {/* Action icon */}
                  <div className={`rounded-lg p-2 ${actionInfo.color}`}>
                    <ActionIcon className="h-5 w-5" />
                  </div>

                  {/* Action details */}
                  <div className="flex-1">
                    <div className="flex items-center gap-2">
                      <h4 className="text-sm font-semibold text-ink">
                        {actionInfo.label}
                      </h4>
                      {getStatusBadge(log.status)}
                    </div>

                    <p className="mt-1 text-xs text-ink-secondary">
                      {formatTimestamp(log.createdAt)}
                    </p>

                    {log.ipAddress && (
                      <p className="mt-1 text-xs text-ink-muted">
                        IP: {log.ipAddress}
                      </p>
                    )}

                    {log.errorMessage && (
                      <p className="mt-2 rounded bg-red-50 p-2 text-xs text-red-700">
                        Error: {log.errorMessage}
                      </p>
                    )}
                  </div>
                </div>
              </div>
            </div>
          )
        })}
      </div>

      {/* Pagination controls */}
      {showPagination && (
        <div className="flex items-center justify-between rounded-lg bg-white shadow-card px-4 py-3">
          <div className="flex items-center gap-2">
            <p className="text-sm text-ink-secondary">
              Showing <span className="font-medium">{page * pageSize + 1}</span> to{' '}
              <span className="font-medium">
                {Math.min((page + 1) * pageSize, totalElements)}
              </span>{' '}
              of <span className="font-medium">{totalElements}</span> entries
            </p>
          </div>

          <div className="flex items-center gap-2">
            <button
              onClick={() => setPage(Math.max(0, page - 1))}
              disabled={page === 0}
              className="rounded border border-hairline px-3 py-1 text-sm font-medium text-ink-secondary hover:bg-surface-hover disabled:cursor-not-allowed disabled:opacity-50"
              aria-label="Previous page"
            >
              <ChevronLeft className="h-4 w-4" />
            </button>

            <span className="text-sm text-ink-secondary">
              Page {page + 1} of {totalPages}
            </span>

            <button
              onClick={() => setPage(Math.min(totalPages - 1, page + 1))}
              disabled={page >= totalPages - 1}
              className="rounded border border-hairline px-3 py-1 text-sm font-medium text-ink-secondary hover:bg-surface-hover disabled:cursor-not-allowed disabled:opacity-50"
              aria-label="Next page"
            >
              <ChevronRight className="h-4 w-4" />
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

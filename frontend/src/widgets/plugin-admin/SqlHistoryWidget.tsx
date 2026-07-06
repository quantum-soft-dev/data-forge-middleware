/**
 * SqlHistoryWidget
 *
 * Widget for the SQL History tab in the admin plugins page.
 * Shows a list of account-plugins and links to their SQL generation history.
 *
 * @module widgets/plugin-admin/SqlHistoryWidget
 */

import { useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { Database, ExternalLink, ChevronLeft, ChevronRight, RefreshCw } from 'lucide-react'
import { Button } from '@/shared/ui/ui/button'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/shared/ui/ui/table'
import { pluginAdminApi } from '@/features/plugin-admin/api/pluginAdminApi'
import type { AdminAccountPlugin } from '@/entities/plugin/model/types'

interface SqlHistoryWidgetProps {
  pluginId?: string
}

export function SqlHistoryWidget({ pluginId }: SqlHistoryWidgetProps) {
  const navigate = useNavigate()
  const [page, setPage] = useState(0)
  const pageSize = 20

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['admin', 'account-plugins', pluginId, page],
    queryFn: () => pluginAdminApi.fetchAccountPlugins(pluginId!, page, pageSize),
    enabled: !!pluginId,
  })

  const handleViewHistory = (accountPlugin: AdminAccountPlugin) => {
    navigate({
      to: '/admin/plugins/$pluginId/accounts/$accountId/history',
      params: {
        pluginId: accountPlugin.pluginId,
        accountId: accountPlugin.accountId,
      },
    })
  }

  const formatDate = (dateStr: string | null) => {
    if (!dateStr) return '-'
    return new Date(dateStr).toLocaleString()
  }

  if (!pluginId) {
    return (
      <div className="rounded-lg bg-brand-50 p-6 text-center">
        <Database className="mx-auto h-12 w-12 text-brand" />
        <h3 className="mt-4 text-lg font-medium text-ink">Select a Plugin</h3>
        <p className="mt-2 text-sm text-ink-secondary">
          Click on a plugin in the Registered Plugins tab to view SQL history for accounts using that plugin.
        </p>
      </div>
    )
  }

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-12">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-brand border-t-transparent" />
      </div>
    )
  }

  if (isError) {
    return (
      <div className="rounded-lg border border-danger-border bg-danger-bg p-6">
        <h3 className="text-lg font-medium text-danger-text">Failed to load account plugins</h3>
        <p className="mt-2 text-sm text-danger-text">
          {error instanceof Error ? error.message : 'An unexpected error occurred'}
        </p>
        <Button
          variant="outline"
          size="sm"
          className="mt-4"
          onClick={() => refetch()}
        >
          <RefreshCw className="mr-2 h-4 w-4" />
          Retry
        </Button>
      </div>
    )
  }

  if (!data || data.content.length === 0) {
    return (
      <div className="rounded-lg bg-white p-6 text-center shadow-panel">
        <Database className="mx-auto h-12 w-12 text-ink-muted" />
        <h3 className="mt-4 text-lg font-medium text-ink">No Accounts Found</h3>
        <p className="mt-2 text-sm text-ink-secondary">
          No accounts have activated this plugin yet.
        </p>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-lg font-medium text-ink">
            Accounts with SQL History
          </h3>
          <p className="text-sm text-ink-secondary">
            {data.totalElements} account{data.totalElements !== 1 ? 's' : ''} using this plugin
          </p>
        </div>
        <Button variant="outline" size="sm" onClick={() => refetch()}>
          <RefreshCw className="mr-2 h-4 w-4" />
          Refresh
        </Button>
      </div>

      {/* Table */}
      <div className="rounded-md border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Account ID</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>Activated</TableHead>
              <TableHead>Last Used</TableHead>
              <TableHead className="text-right">SQL Generations</TableHead>
              <TableHead className="text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.content.map((accountPlugin) => (
              <TableRow key={accountPlugin.accountId}>
                <TableCell className="font-mono text-sm">
                  {accountPlugin.accountId.substring(0, 8)}...
                </TableCell>
                <TableCell>
                  <span
                    className={`inline-flex items-center rounded-full px-2 py-1 text-xs font-medium ${
                      accountPlugin.isActive
                        ? 'bg-transparent'
                        : 'bg-surface-subtle text-ink'
                    }`}
                  >
                    {accountPlugin.isActive ? 'Active' : 'Inactive'}
                  </span>
                </TableCell>
                <TableCell className="text-sm text-ink-secondary">
                  {formatDate(accountPlugin.activatedAt)}
                </TableCell>
                <TableCell className="text-sm text-ink-secondary">
                  {formatDate(accountPlugin.lastUsedAt)}
                </TableCell>
                <TableCell className="text-right font-medium">
                  {accountPlugin.generationCount}
                </TableCell>
                <TableCell className="text-right">
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => handleViewHistory(accountPlugin)}
                    disabled={accountPlugin.generationCount === 0}
                  >
                    <ExternalLink className="mr-2 h-4 w-4" />
                    View History
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>

      {/* Pagination */}
      {data.totalPages > 1 && (
        <div className="flex items-center justify-between border-t pt-4">
          <p className="text-sm text-ink-secondary">
            Page {data.page + 1} of {data.totalPages}
          </p>
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              disabled={data.first}
            >
              <ChevronLeft className="h-4 w-4" />
              Previous
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPage((p) => p + 1)}
              disabled={data.last}
            >
              Next
              <ChevronRight className="ml-2 h-4 w-4" />
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}

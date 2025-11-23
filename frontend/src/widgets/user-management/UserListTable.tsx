/**
 * UserListTable Component
 *
 * Per T031: Table for user management with Keycloak integration.
 * Uses TanStack Table with pagination, sorting, and filtering.
 *
 * Features:
 * - Displays AccountWithKeycloakStatus (includes Keycloak fields)
 * - Status badges for active, keycloak, password states
 * - Sortable columns
 * - Pagination controls
 * - Action buttons (view details, lock/unlock, reset password)
 * - Loading and empty states
 */

import {
  createColumnHelper,
  flexRender,
  getCoreRowModel,
  getSortedRowModel,
  useReactTable,
  type SortingState,
} from '@tanstack/react-table'
import { useState } from 'react'
import { formatDistanceToNow } from 'date-fns'
import { ArrowUpDown, Eye, Lock, Unlock, KeyRound } from 'lucide-react'
import type { AccountWithKeycloakStatus } from '@/entities/account/model/types'
import { AccountStatusBadge } from '@/entities/account/ui/AccountStatusBadge'
import { Pagination } from '../account-table/Pagination'

interface UserListTableProps {
  users: AccountWithKeycloakStatus[]
  totalCount: number
  page: number // 1-indexed for UI
  pageSize: number
  onPageChange: (page: number) => void
  onPageSizeChange: (size: number) => void
  onViewDetails?: (user: AccountWithKeycloakStatus) => void
  onLock?: (user: AccountWithKeycloakStatus) => void
  onUnlock?: (user: AccountWithKeycloakStatus) => void
  onResetPassword?: (user: AccountWithKeycloakStatus) => void
  isLoading?: boolean
}

const columnHelper = createColumnHelper<AccountWithKeycloakStatus>()

export function UserListTable({
  users,
  totalCount,
  page,
  pageSize,
  onPageChange,
  onPageSizeChange,
  onViewDetails,
  onLock,
  onUnlock,
  onResetPassword,
  isLoading = false,
}: UserListTableProps) {
  const [sorting, setSorting] = useState<SortingState>([])

  const columns = [
    columnHelper.accessor('name', {
      header: ({ column }) => (
        <button
          onClick={() => column.toggleSorting()}
          className="flex items-center gap-1 hover:text-gray-900"
        >
          Name
          <ArrowUpDown className="h-3 w-3" />
        </button>
      ),
      cell: (info) => (
        <div className="font-medium text-gray-900">{info.getValue()}</div>
      ),
    }),
    columnHelper.accessor('email', {
      header: ({ column }) => (
        <button
          onClick={() => column.toggleSorting()}
          className="flex items-center gap-1 hover:text-gray-900"
        >
          Email
          <ArrowUpDown className="h-3 w-3" />
        </button>
      ),
      cell: (info) => (
        <div className="text-sm text-gray-600">{info.getValue()}</div>
      ),
    }),
    columnHelper.display({
      id: 'status',
      header: 'Status',
      cell: (info) => {
        const user = info.row.original
        return (
          <AccountStatusBadge
            isActive={user.isActive}
            isBlocked={user.isBlocked}
          />
        )
      },
    }),
    columnHelper.accessor('createdAt', {
      header: ({ column }) => (
        <button
          onClick={() => column.toggleSorting()}
          className="flex items-center gap-1 hover:text-gray-900"
        >
          Created
          <ArrowUpDown className="h-3 w-3" />
        </button>
      ),
      cell: (info) => {
        try {
          const date = new Date(info.getValue())
          return (
            <div className="text-sm text-gray-500">
              {formatDistanceToNow(date, { addSuffix: true })}
            </div>
          )
        } catch {
          return <div className="text-sm text-gray-500">—</div>
        }
      },
    }),
    columnHelper.accessor('lastLogin', {
      header: 'Last Login',
      cell: (info) => {
        const lastLogin = info.getValue()
        if (!lastLogin) return <div className="text-sm text-gray-400">Never</div>

        try {
          const date = new Date(lastLogin)
          return (
            <div className="text-sm text-gray-500">
              {formatDistanceToNow(date, { addSuffix: true })}
            </div>
          )
        } catch {
          return <div className="text-sm text-gray-500">—</div>
        }
      },
    }),
    columnHelper.display({
      id: 'actions',
      header: 'Actions',
      cell: (info) => {
        const user = info.row.original
        const hasAuth0 = !!user.identityProviderUserId

        return (
          <div className="flex items-center gap-1">
            {onViewDetails && (
              <button
                onClick={() => onViewDetails(user)}
                className="rounded p-1.5 text-gray-600 hover:bg-gray-100 hover:text-gray-900"
                title="View details"
              >
                <Eye className="h-4 w-4" />
              </button>
            )}

            {hasAuth0 && !user.isBlocked && onLock && (
              <button
                onClick={() => onLock(user)}
                className="rounded p-1.5 text-orange-600 hover:bg-orange-50 hover:text-orange-900"
                title="Lock account"
              >
                <Lock className="h-4 w-4" />
              </button>
            )}

            {hasAuth0 && user.isBlocked && onUnlock && (
              <button
                onClick={() => onUnlock(user)}
                className="rounded p-1.5 text-green-600 hover:bg-green-50 hover:text-green-900"
                title="Unlock account"
              >
                <Unlock className="h-4 w-4" />
              </button>
            )}

            {hasKeycloak && onResetPassword && (
              <button
                onClick={() => onResetPassword(user)}
                className="rounded p-1.5 text-blue-600 hover:bg-blue-50 hover:text-blue-900"
                title="Reset password"
              >
                <KeyRound className="h-4 w-4" />
              </button>
            )}
          </div>
        )
      },
    }),
  ]

  const table = useReactTable({
    data: users,
    columns,
    state: {
      sorting,
    },
    onSortingChange: setSorting,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
    manualPagination: true,
    pageCount: Math.ceil(totalCount / pageSize),
  })

  if (isLoading) {
    return (
      <div className="rounded-lg border border-gray-200 bg-white p-8">
        <div className="flex items-center justify-center">
          <div className="h-8 w-8 animate-spin rounded-full border-4 border-gray-200 border-t-blue-600" />
          <span className="ml-3 text-sm text-gray-600">Loading users...</span>
        </div>
      </div>
    )
  }

  if (users.length === 0) {
    return (
      <div className="rounded-lg border border-gray-200 bg-white p-8 text-center">
        <p className="text-sm text-gray-500">No users found</p>
      </div>
    )
  }

  return (
    <div className="overflow-hidden rounded-lg border border-gray-200 bg-white shadow-sm">
      <div className="overflow-x-auto">
        <table className="min-w-full divide-y divide-gray-200">
          <thead className="bg-gray-50">
            {table.getHeaderGroups().map((headerGroup) => (
              <tr key={headerGroup.id}>
                {headerGroup.headers.map((header) => (
                  <th
                    key={header.id}
                    className="px-6 py-3 text-left text-xs font-medium uppercase tracking-wider text-gray-500"
                  >
                    {header.isPlaceholder
                      ? null
                      : flexRender(
                          header.column.columnDef.header,
                          header.getContext()
                        )}
                  </th>
                ))}
              </tr>
            ))}
          </thead>
          <tbody className="divide-y divide-gray-200 bg-white">
            {table.getRowModel().rows.map((row) => (
              <tr key={row.id} className="hover:bg-gray-50">
                {row.getVisibleCells().map((cell) => (
                  <td key={cell.id} className="whitespace-nowrap px-6 py-4">
                    {flexRender(cell.column.columnDef.cell, cell.getContext())}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <Pagination
        page={page}
        totalPages={Math.ceil(totalCount / pageSize)}
        pageSize={pageSize}
        totalElements={totalCount}
        onPageChange={onPageChange}
        onPageSizeChange={onPageSizeChange}
      />
    </div>
  )
}

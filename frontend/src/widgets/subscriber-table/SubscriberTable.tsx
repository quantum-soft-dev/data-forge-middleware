/**
 * SubscriberTable Component
 *
 * Displays subscriber list in a table with TanStack Table.
 * Supports pagination, loading states, and empty states.
 */

import {
  createColumnHelper,
  flexRender,
  getCoreRowModel,
  useReactTable,
} from '@tanstack/react-table'
import { formatDistanceToNow } from 'date-fns'
import type { Subscriber } from '@/entities/subscriber/model/types'
import { Pagination } from './Pagination'

interface SubscriberTableProps {
  subscribers: Subscriber[]
  totalCount: number
  page: number // 1-indexed
  pageSize: number
  onPageChange: (page: number) => void
  onPageSizeChange: (size: number) => void
  isLoading?: boolean
}

const columnHelper = createColumnHelper<Subscriber>()

const columns = [
  columnHelper.accessor('name', {
    header: 'Name',
    cell: (info) => (
      <div className="font-medium text-gray-900">{info.getValue()}</div>
    ),
  }),
  columnHelper.accessor('email', {
    header: 'Email',
    cell: (info) => (
      <div className="text-gray-600">{info.getValue()}</div>
    ),
  }),
  columnHelper.accessor('phone', {
    header: 'Phone',
    cell: (info) => (
      <div className="text-gray-600">{info.getValue() || '—'}</div>
    ),
  }),
  columnHelper.accessor('company', {
    header: 'Company',
    cell: (info) => (
      <div className="text-gray-600">{info.getValue() || '—'}</div>
    ),
  }),
  columnHelper.accessor('status', {
    header: 'Status',
    cell: (info) => {
      const status = info.getValue()
      return (
        <span
          className={`inline-flex rounded-full px-2 py-1 text-xs font-semibold ${
            status === 'active'
              ? 'bg-green-100 text-green-800'
              : 'bg-gray-100 text-gray-800'
          }`}
        >
          {status === 'active' ? 'Active' : 'Inactive'}
        </span>
      )
    },
  }),
  columnHelper.accessor('createdAt', {
    header: 'Created',
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
]

export function SubscriberTable({
  subscribers,
  totalCount,
  page,
  pageSize,
  onPageChange,
  onPageSizeChange,
  isLoading = false,
}: SubscriberTableProps) {
  const table = useReactTable({
    data: subscribers,
    columns,
    getCoreRowModel: getCoreRowModel(),
    manualPagination: true,
    pageCount: Math.ceil(totalCount / pageSize),
  })

  const totalPages = Math.ceil(totalCount / pageSize)

  if (isLoading) {
    return (
      <div className="overflow-hidden rounded-lg border border-gray-200 bg-white shadow">
        <div className="px-6 py-12 text-center">
          {/* Skeleton rows */}
          {[...Array(5)].map((_, i) => (
            <div
              key={i}
              data-testid={`skeleton-row-${i}`}
              className="mb-4 h-12 animate-pulse rounded bg-gray-100"
            />
          ))}
        </div>
      </div>
    )
  }

  if (subscribers.length === 0) {
    return (
      <div className="overflow-hidden rounded-lg border border-gray-200 bg-white shadow">
        <div className="px-6 py-12 text-center">
          <p className="text-sm text-gray-500">
            No accounts found. Try adjusting your filters.
          </p>
        </div>
      </div>
    )
  }

  return (
    <div className="overflow-hidden rounded-lg border border-gray-200 bg-white shadow">
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
        pageSize={pageSize}
        totalElements={totalCount}
        totalPages={totalPages}
        onPageChange={onPageChange}
        onPageSizeChange={onPageSizeChange}
      />
    </div>
  )
}

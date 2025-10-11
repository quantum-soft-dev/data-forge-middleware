/**
 * Subscriber List Page
 *
 * Displays paginated list of subscribers with search and filter capabilities.
 * Per spec FR-010: View subscriber list with pagination, search, and filter.
 */

import { useState } from 'react'
import { Header } from '@/widgets/header/Header'
import { SubscriberTable } from '@/widgets/subscriber-table/SubscriberTable'
import { SearchInput } from '@/features/subscriber-search/SearchInput'
import { StatusFilter } from '@/features/subscriber-search/StatusFilter'
import { useSubscribers } from '@/entities/subscriber/api/useSubscribers'
import type { SubscriberStatus } from '@/entities/subscriber/model/types'

export default function SubscriberListPage() {
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)
  const [search, setSearch] = useState('')
  const [status, setStatus] = useState<SubscriberStatus | 'all'>('all')

  const { data, isLoading, isError, error } = useSubscribers({
    page,
    pageSize,
    search: search || undefined,
    status: status === 'all' ? undefined : status,
  })

  const handlePageChange = (newPage: number) => {
    setPage(newPage)
  }

  const handlePageSizeChange = (newSize: number) => {
    setPageSize(newSize)
    setPage(1) // Reset to first page on size change
  }

  const handleSearch = (query: string) => {
    setSearch(query)
    setPage(1) // Reset to first page on search
  }

  const handleStatusChange = (newStatus: SubscriberStatus | 'all') => {
    setStatus(newStatus)
    setPage(1) // Reset to first page on filter change
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <Header />

      <main className="mx-auto max-w-7xl px-4 py-8 sm:px-6 lg:px-8">
        {/* Page header */}
        <div className="mb-8">
          <h1 className="text-3xl font-bold text-gray-900">Subscribers</h1>
          <p className="mt-2 text-sm text-gray-600">
            Manage your subscriber database
          </p>
        </div>

        {/* Filters */}
        <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
          <div className="w-full sm:w-96">
            <SearchInput onSearch={handleSearch} />
          </div>
          <StatusFilter value={status} onChange={handleStatusChange} />
        </div>

        {/* Error state */}
        {isError && (
          <div className="mb-6 rounded-lg border border-red-200 bg-red-50 p-4">
            <p className="text-sm font-medium text-red-800">
              {error instanceof Error ? error.message : 'Failed to load subscribers'}
            </p>
          </div>
        )}

        {/* Table */}
        <SubscriberTable
          subscribers={data?.content ?? []}
          totalCount={data?.totalElements ?? 0}
          page={page}
          pageSize={pageSize}
          onPageChange={handlePageChange}
          onPageSizeChange={handlePageSizeChange}
          isLoading={isLoading}
        />
      </main>
    </div>
  )
}

/**
 * Pagination Component
 *
 * Pagination controls with page numbers, prev/next buttons, and page size selector.
 */

import { ChevronLeft, ChevronRight } from 'lucide-react'

interface PaginationProps {
  page: number // 1-indexed
  pageSize: number
  totalElements: number
  totalPages: number
  onPageChange: (page: number) => void
  onPageSizeChange: (size: number) => void
}

export function Pagination({
  page,
  pageSize,
  totalElements,
  totalPages,
  onPageChange,
  onPageSizeChange,
}: PaginationProps) {
  const startIndex = (page - 1) * pageSize + 1
  const endIndex = Math.min(page * pageSize, totalElements)

  return (
    <div className="flex items-center justify-between border-t border-separator bg-white px-4 py-3 sm:px-6">
      {/* Results info */}
      <div className="flex flex-1 justify-between sm:hidden">
        <span className="text-sm text-ink-secondary">
          Page {page} of {totalPages}
        </span>
      </div>

      <div className="hidden sm:flex sm:flex-1 sm:items-center sm:justify-between">
        <div className="flex items-center gap-4">
          <p className="text-sm text-ink-secondary">
            Showing <span className="font-medium">{startIndex}</span> to{' '}
            <span className="font-medium">{endIndex}</span> of{' '}
            <span className="font-medium">{totalElements}</span> results
          </p>

          {/* Page size selector */}
          <div className="flex items-center gap-2">
            <label htmlFor="page-size" className="text-sm text-ink-secondary">
              Per page:
            </label>
            <select
              id="page-size"
              value={pageSize}
              onChange={(e) => onPageSizeChange(Number(e.target.value))}
              className="h-8 rounded-lg border border-input bg-background px-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            >
              <option value={10}>10</option>
              <option value={20}>20</option>
              <option value={50}>50</option>
              <option value={100}>100</option>
            </select>
          </div>
        </div>

        {/* Page navigation */}
        <div className="flex items-center gap-2">
          <button
            onClick={() => onPageChange(page - 1)}
            disabled={page <= 1}
            aria-label="Previous page"
            className="relative inline-flex items-center rounded-lg border border-hairline bg-white px-3 py-2 text-sm font-medium text-ink hover:bg-secondary disabled:cursor-not-allowed disabled:opacity-50"
          >
            <ChevronLeft className="h-4 w-4" />
            <span className="ml-1">Previous</span>
          </button>

          <span className="text-sm text-ink-secondary">
            Page {page} of {totalPages}
          </span>

          <button
            onClick={() => onPageChange(page + 1)}
            disabled={page >= totalPages}
            aria-label="Next page"
            className="relative inline-flex items-center rounded-lg border border-hairline bg-white px-3 py-2 text-sm font-medium text-ink hover:bg-secondary disabled:cursor-not-allowed disabled:opacity-50"
          >
            <span className="mr-1">Next</span>
            <ChevronRight className="h-4 w-4" />
          </button>
        </div>
      </div>
    </div>
  )
}

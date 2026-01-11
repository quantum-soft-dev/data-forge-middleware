/**
 * GlobalErrorList Component
 *
 * Paginated list of global errors with checkbox selection.
 */

import { useState, useCallback } from 'react'
import { toast } from 'sonner'
import { Button } from '@/shared/ui/ui/button'
import { Checkbox } from '@/shared/ui/ui/checkbox'
import { Skeleton } from '@/shared/ui/ui/skeleton'
import { GlobalErrorItem } from './GlobalErrorItem'
import { GlobalErrorDetails } from './GlobalErrorDetails'
import type { GlobalErrorPageResponse } from '../model/global-error.types'
import { useGlobalError, useMarkMultipleAsRead } from '../api/global-errors.queries'

interface GlobalErrorListProps {
  data: GlobalErrorPageResponse | undefined
  isLoading: boolean
  page: number
  onPageChange: (page: number) => void
}

export function GlobalErrorList({ data, isLoading, page, onPageChange }: GlobalErrorListProps) {
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())
  const [detailsOpen, setDetailsOpen] = useState(false)
  const [selectedErrorId, setSelectedErrorId] = useState<string | null>(null)

  const { data: selectedError, isLoading: isLoadingDetails } = useGlobalError(selectedErrorId ?? '')
  const markMultiple = useMarkMultipleAsRead()

  const handleSelectAll = (checked: boolean) => {
    if (checked && data) {
      setSelectedIds(new Set(data.content.map(e => e.id)))
    } else {
      setSelectedIds(new Set())
    }
  }

  const handleSelect = useCallback((id: string, selected: boolean) => {
    setSelectedIds(prev => {
      const next = new Set(prev)
      if (selected) {
        next.add(id)
      } else {
        next.delete(id)
      }
      return next
    })
  }, [])

  const handleItemClick = (id: string) => {
    setSelectedErrorId(id)
    setDetailsOpen(true)
  }

  const handleMarkSelectedAsRead = () => {
    if (selectedIds.size > 0) {
      const requestedCount = selectedIds.size
      markMultiple.mutate(Array.from(selectedIds), {
        onSuccess: (data) => {
          // Handle partial failure: some errors may have already been read or inaccessible
          if (data.markedCount < requestedCount) {
            toast.warning('Partial update', {
              description: `Only ${data.markedCount} of ${requestedCount} errors were marked as read`,
            })
          }
          setSelectedIds(new Set())
        },
      })
    }
  }

  if (isLoading) {
    return (
      <div className="space-y-2">
        {[...Array(5)].map((_, i) => (
          <div key={i} className="flex items-center gap-4 p-3">
            <Skeleton className="h-4 w-4" />
            <div className="flex-1 space-y-2">
              <Skeleton className="h-4 w-3/4" />
              <Skeleton className="h-3 w-1/2" />
            </div>
          </div>
        ))}
      </div>
    )
  }

  if (!data || data.content.length === 0) {
    return (
      <div className="text-center py-8 text-gray-500">
        No global errors found
      </div>
    )
  }

  const allSelected = data.content.length > 0 && selectedIds.size === data.content.length
  const someSelected = selectedIds.size > 0 && selectedIds.size < data.content.length

  return (
    <div>
      {/* Header with bulk actions */}
      <div className="flex items-center justify-between p-3 border-b bg-gray-50">
        <div className="flex items-center gap-3">
          <Checkbox
            checked={allSelected}
            onCheckedChange={handleSelectAll}
            aria-label="Select all errors"
            className={someSelected ? 'data-[state=checked]:bg-gray-400' : ''}
          />
          <span className="text-sm text-gray-600">
            {selectedIds.size > 0
              ? `${selectedIds.size} selected`
              : `${data.totalElements} errors`}
          </span>
        </div>

        {selectedIds.size > 0 && (
          <Button
            variant="outline"
            size="sm"
            onClick={handleMarkSelectedAsRead}
            disabled={markMultiple.isPending}
          >
            {markMultiple.isPending ? 'Marking...' : 'Mark Selected as Read'}
          </Button>
        )}
      </div>

      {/* Error list */}
      <div className="divide-y">
        {data.content.map(error => (
          <GlobalErrorItem
            key={error.id}
            error={error}
            selected={selectedIds.has(error.id)}
            onSelect={handleSelect}
            onClick={handleItemClick}
          />
        ))}
      </div>

      {/* Pagination */}
      {data.totalPages > 1 && (
        <div className="flex items-center justify-between p-3 border-t">
          <span className="text-sm text-gray-600">
            Page {page + 1} of {data.totalPages}
          </span>
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => onPageChange(page - 1)}
              disabled={page === 0}
            >
              Previous
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => onPageChange(page + 1)}
              disabled={page >= data.totalPages - 1}
            >
              Next
            </Button>
          </div>
        </div>
      )}

      {/* Error details modal */}
      <GlobalErrorDetails
        error={selectedError}
        open={detailsOpen && !isLoadingDetails && !!selectedError}
        onOpenChange={setDetailsOpen}
      />
    </div>
  )
}

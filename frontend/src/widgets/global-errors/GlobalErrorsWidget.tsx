/**
 * GlobalErrorsWidget Component
 *
 * Dashboard widget for displaying global errors with unread badge.
 */

import { useState } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/shared/ui/ui/card'
import { Badge } from '@/shared/ui/ui/badge'
import { Button } from '@/shared/ui/ui/button'
import { GlobalErrorList } from '@/features/global-errors/ui/GlobalErrorList'
import {
  useGlobalErrors,
  useUnreadCount,
  useMarkAllAsRead,
} from '@/features/global-errors/api/global-errors.queries'

/** Maximum count displayed in badge; higher counts show as "99+" */
const MAX_BADGE_COUNT = 99

interface GlobalErrorsWidgetProps {
  /** Initial page size */
  pageSize?: number
}

export function GlobalErrorsWidget({ pageSize = 10 }: GlobalErrorsWidgetProps) {
  const [page, setPage] = useState(0)
  const [unreadOnly, setUnreadOnly] = useState(false)

  const { data: errorsData, isLoading: isLoadingErrors } = useGlobalErrors({
    page,
    size: pageSize,
    unreadOnly,
  })

  const { data: unreadData } = useUnreadCount()
  const markAllAsRead = useMarkAllAsRead()

  const unreadCount = unreadData?.count ?? 0

  const handleMarkAllAsRead = () => {
    markAllAsRead.mutate()
  }

  const toggleUnreadFilter = () => {
    setUnreadOnly(!unreadOnly)
    setPage(0) // Reset to first page when changing filter
  }

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-4">
        <div className="flex items-center gap-2">
          <CardTitle>Global Errors</CardTitle>
          {unreadCount > 0 && (
            <Badge variant="critical" className="px-2">
              {unreadCount > MAX_BADGE_COUNT ? `${MAX_BADGE_COUNT}+` : unreadCount}
            </Badge>
          )}
        </div>

        <div className="flex items-center gap-2">
          <Button
            variant={unreadOnly ? 'default' : 'outline'}
            size="sm"
            onClick={toggleUnreadFilter}
          >
            {unreadOnly ? 'Show All' : 'Unread Only'}
          </Button>

          {unreadCount > 0 && (
            <Button
              variant="outline"
              size="sm"
              onClick={handleMarkAllAsRead}
              disabled={markAllAsRead.isPending}
            >
              {markAllAsRead.isPending ? 'Marking...' : 'Mark All as Read'}
            </Button>
          )}
        </div>
      </CardHeader>

      <CardContent className="p-0">
        <GlobalErrorList
          data={errorsData}
          isLoading={isLoadingErrors}
          page={page}
          onPageChange={setPage}
          unreadOnly={unreadOnly}
        />
      </CardContent>
    </Card>
  )
}

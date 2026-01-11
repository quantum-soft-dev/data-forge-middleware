/**
 * GlobalErrorDetails Component
 *
 * Modal component for displaying full error details.
 */

import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/shared/ui/ui/dialog'
import { Badge } from '@/shared/ui/ui/badge'
import { Button } from '@/shared/ui/ui/button'
import { Separator } from '@/shared/ui/ui/separator'
import { format } from 'date-fns'
import type { GlobalErrorResponse, ErrorSeverity } from '../model/global-error.types'
import { useMarkAsRead } from '../api/global-errors.queries'

interface GlobalErrorDetailsProps {
  error: GlobalErrorResponse | null | undefined
  open: boolean
  onOpenChange: (open: boolean) => void
}

const SEVERITY_VARIANTS: Record<ErrorSeverity, 'destructive' | 'default' | 'secondary' | 'outline'> = {
  CRITICAL: 'destructive',
  ERROR: 'destructive',
  WARNING: 'secondary',
  INFO: 'outline',
}

export function GlobalErrorDetails({ error, open, onOpenChange }: GlobalErrorDetailsProps) {
  const markAsRead = useMarkAsRead()

  if (!error) return null

  const handleMarkAsRead = () => {
    if (!error.isRead) {
      markAsRead.mutate(error.id)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl max-h-[80vh] overflow-y-auto">
        <DialogHeader>
          <div className="flex items-center gap-2">
            <Badge variant={SEVERITY_VARIANTS[error.severity]}>
              {error.severity}
            </Badge>
            {!error.isRead && (
              <Badge variant="outline" className="text-blue-600 border-blue-600">
                Unread
              </Badge>
            )}
          </div>
          <DialogTitle className="text-lg">{error.title}</DialogTitle>
          <DialogDescription>
            {error.siteName} • {error.type} • {format(new Date(error.occurredAt), 'PPpp')}
          </DialogDescription>
        </DialogHeader>

        <Separator />

        <div className="space-y-4">
          <div>
            <h4 className="text-sm font-medium text-gray-500 mb-1">Message</h4>
            <p className="text-sm whitespace-pre-wrap">{error.message}</p>
          </div>

          {error.stackTrace && (
            <div>
              <h4 className="text-sm font-medium text-gray-500 mb-1">Stack Trace</h4>
              <pre className="text-xs bg-gray-100 p-3 rounded overflow-x-auto whitespace-pre-wrap">
                {error.stackTrace}
              </pre>
            </div>
          )}

          {error.metadata && Object.keys(error.metadata).length > 0 && (
            <div>
              <h4 className="text-sm font-medium text-gray-500 mb-1">Metadata</h4>
              <pre className="text-xs bg-gray-100 p-3 rounded overflow-x-auto">
                {JSON.stringify(error.metadata, null, 2)}
              </pre>
            </div>
          )}

          {error.clientVersion && (
            <div>
              <h4 className="text-sm font-medium text-gray-500 mb-1">Client Version</h4>
              <p className="text-sm">{error.clientVersion}</p>
            </div>
          )}
        </div>

        <div className="flex justify-end gap-2 mt-4">
          {!error.isRead && (
            <Button
              variant="outline"
              onClick={handleMarkAsRead}
              disabled={markAsRead.isPending}
            >
              {markAsRead.isPending ? 'Marking...' : 'Mark as Read'}
            </Button>
          )}
          <Button variant="secondary" onClick={() => onOpenChange(false)}>
            Close
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  )
}

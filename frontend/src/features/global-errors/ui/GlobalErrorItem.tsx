/**
 * GlobalErrorItem Component
 *
 * Single row component for displaying a global error in a list.
 */

import { Badge } from '@/shared/ui/ui/badge'
import { Checkbox } from '@/shared/ui/ui/checkbox'
import { formatDistanceToNow } from 'date-fns'
import type { GlobalErrorSummary, ErrorSeverity } from '../model/global-error.types'

interface GlobalErrorItemProps {
  error: GlobalErrorSummary
  selected: boolean
  onSelect: (id: string, selected: boolean) => void
  onClick: (id: string) => void
}

const SEVERITY_VARIANTS: Record<ErrorSeverity, 'critical' | 'warning' | 'info' | 'neutral'> = {
  CRITICAL: 'critical',
  ERROR: 'critical',
  WARNING: 'warning',
  INFO: 'neutral',
}

export function GlobalErrorItem({ error, selected, onSelect, onClick }: GlobalErrorItemProps) {
  const handleCheckboxChange = (checked: boolean) => {
    onSelect(error.id, checked)
  }

  const handleRowClick = (e: React.MouseEvent) => {
    // Don't trigger row click when clicking checkbox
    if ((e.target as HTMLElement).closest('[role="checkbox"]')) {
      return
    }
    onClick(error.id)
  }

  return (
    <div
      className={`flex items-center gap-4 p-3 border-b border-separator cursor-pointer hover:bg-surface-hover transition-colors ${
        !error.isRead ? 'bg-brand-50/50' : ''
      }`}
      onClick={handleRowClick}
    >
      <Checkbox
        checked={selected}
        onCheckedChange={handleCheckboxChange}
        aria-label={`Select error ${error.title}`}
      />

      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <Badge variant={SEVERITY_VARIANTS[error.severity]}>
            {error.severity}
          </Badge>
          {!error.isRead && (
            <span className="w-2 h-2 rounded-full bg-brand" aria-label="Unread" />
          )}
          <span className="text-sm font-medium truncate">{error.title}</span>
        </div>
        <div className="flex items-center gap-2 mt-1 text-xs text-ink-secondary">
          <span>{error.siteName}</span>
          <span>•</span>
          <span>{error.type}</span>
          <span>•</span>
          <time dateTime={error.occurredAt}>
            {formatDistanceToNow(new Date(error.occurredAt), { addSuffix: true })}
          </time>
        </div>
      </div>
    </div>
  )
}

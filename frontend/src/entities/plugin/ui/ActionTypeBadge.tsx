/**
 * ActionTypeBadge Component
 *
 * Displays action type as a monitoring pill (024, T036).
 */

import { Badge, type BadgeProps } from '@/shared/ui/ui/badge'
import type { PluginActionType } from '../model/types'

interface ActionTypeBadgeProps {
  actionType: PluginActionType
  className?: string
}

const ACTION_TYPE_VARIANTS: Record<PluginActionType, BadgeProps['variant']> = {
  ACTIVATE: 'success',
  DEACTIVATE: 'neutral',
  REACTIVATE: 'info',
  EVENT_DISPATCHED: 'info',
  EVENT_FAILED: 'critical',
  EVENT_TIMEOUT: 'stalled',
}

const ACTION_TYPE_LABELS: Record<PluginActionType, string> = {
  ACTIVATE: 'Activate',
  DEACTIVATE: 'Deactivate',
  REACTIVATE: 'Reactivate',
  EVENT_DISPATCHED: 'Event Dispatched',
  EVENT_FAILED: 'Event Failed',
  EVENT_TIMEOUT: 'Event Timeout',
}

export function ActionTypeBadge({ actionType, className = '' }: ActionTypeBadgeProps) {
  return (
    <Badge variant={ACTION_TYPE_VARIANTS[actionType] ?? 'neutral'} className={className}>
      {ACTION_TYPE_LABELS[actionType] || actionType}
    </Badge>
  )
}

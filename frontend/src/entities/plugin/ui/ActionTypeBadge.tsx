/**
 * ActionTypeBadge Component
 *
 * Displays action type as a monitoring pill (024, T036).
 */

import { Badge } from '@/shared/ui/ui/badge'
import { actionTypeLabel, actionTypeVariant } from '../model/actionTypes'
import type { PluginActionType } from '../model/types'

interface ActionTypeBadgeProps {
  actionType: PluginActionType
  className?: string
}

export function ActionTypeBadge({ actionType, className = '' }: ActionTypeBadgeProps) {
  return (
    <Badge variant={actionTypeVariant(actionType)} className={className}>
      {actionTypeLabel(actionType)}
    </Badge>
  )
}

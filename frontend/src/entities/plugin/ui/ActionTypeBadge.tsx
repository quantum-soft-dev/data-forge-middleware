/**
 * ActionTypeBadge Component
 *
 * Displays action type with semantic colors.
 */

import type { PluginActionType } from '../model/types'

interface ActionTypeBadgeProps {
  actionType: PluginActionType
  className?: string
}

const ACTION_TYPE_STYLES: Record<PluginActionType, string> = {
  ACTIVATE: 'bg-green-100 text-green-800',
  DEACTIVATE: 'bg-yellow-100 text-yellow-800',
  REACTIVATE: 'bg-blue-100 text-blue-800',
  EVENT_DISPATCHED: 'bg-purple-100 text-purple-800',
  EVENT_FAILED: 'bg-red-100 text-red-800',
  EVENT_TIMEOUT: 'bg-orange-100 text-orange-800',
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
    <span
      className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${
        ACTION_TYPE_STYLES[actionType] || 'bg-gray-100 text-gray-800'
      } ${className}`}
    >
      {ACTION_TYPE_LABELS[actionType] || actionType}
    </span>
  )
}

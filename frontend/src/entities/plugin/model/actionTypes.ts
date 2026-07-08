/**
 * Shared plugin action-type → pill mapping (review r3 dedup).
 *
 * Single source for the short-form labels and Badge variants used by
 * ActionTypeBadge and the audit-log filter dropdown; PluginLogsTab keeps its
 * own long-form copy on purpose (different product wording, e.g.
 * "Plugin Activated" / "Generating SQL...").
 */

import type { BadgeProps } from '@/shared/ui/ui/badge'
import type { PluginActionType } from './types'

export const ACTION_TYPE_LABELS: Record<PluginActionType, string> = {
  ACTIVATE: 'Activate',
  DEACTIVATE: 'Deactivate',
  REACTIVATE: 'Reactivate',
  EVENT_DISPATCHED: 'Event Dispatched',
  EVENT_FAILED: 'Event Failed',
  EVENT_TIMEOUT: 'Event Timeout',
}

export const ACTION_TYPE_VARIANTS: Record<PluginActionType, BadgeProps['variant']> = {
  ACTIVATE: 'success',
  DEACTIVATE: 'neutral',
  REACTIVATE: 'info',
  EVENT_DISPATCHED: 'info',
  EVENT_FAILED: 'critical',
  EVENT_TIMEOUT: 'stalled',
}

/** Fallback-safe accessors: an action type the maps lag behind renders readably. */
export function actionTypeLabel(actionType: string): string {
  return ACTION_TYPE_LABELS[actionType as PluginActionType] ?? actionType
}

export function actionTypeVariant(actionType: string): NonNullable<BadgeProps['variant']> {
  return ACTION_TYPE_VARIANTS[actionType as PluginActionType] ?? 'neutral'
}

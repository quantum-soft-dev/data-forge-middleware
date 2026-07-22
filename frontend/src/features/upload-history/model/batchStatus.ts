/**
 * Shared batch status → pill mapping (monitoring language).
 *
 * Single source for every surface that renders a batch status pill
 * (BatchListView, BatchDetailView, DeltaBatchDetail) so a status can never
 * be green on one screen and amber on another.
 */

import type { BadgeProps } from '@/shared/ui/ui/badge';
import { severityTokens, type SeverityToken } from '@/shared/ui/tokens';

export const STATUS_LABELS: Record<string, string> = {
  COMPLETED: 'Completed',
  COMPLETED_WITH_WARNINGS: 'Completed (Warnings)',
  IN_PROGRESS: 'In progress',
  FAILED: 'Failed',
  NOT_COMPLETED: 'Not completed',
  CANCELLED: 'Cancelled',
};

export const STATUS_VARIANT: Record<string, NonNullable<BadgeProps['variant']>> = {
  COMPLETED: 'success',
  COMPLETED_WITH_WARNINGS: 'warning',
  IN_PROGRESS: 'info',
  FAILED: 'critical',
  NOT_COMPLETED: 'stalled',
  CANCELLED: 'stalled',
};

/**
 * Icon/circle tone for a failure-family status: stalled statuses (timeout, cancel) render
 * amber, everything else red. Lives here so the status icons follow the variant map — an
 * inline ternary per surface would go stale on the next variant remap (review r3).
 */
export function failureTone(status: string): SeverityToken {
  return STATUS_VARIANT[status] === 'stalled' ? severityTokens.stalled : severityTokens.critical;
}

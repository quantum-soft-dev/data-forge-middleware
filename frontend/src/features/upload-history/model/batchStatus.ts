/**
 * Shared batch status → pill mapping (monitoring language).
 *
 * Single source for every surface that renders a batch status pill
 * (BatchListView, BatchDetailView, DeltaBatchDetail) so a status can never
 * be green on one screen and amber on another.
 */

import type { BadgeProps } from '@/shared/ui/ui/badge';

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

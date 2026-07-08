/**
 * Shared comparison status → pill mapping (monitoring language).
 *
 * Single source for every surface that renders a comparison status pill
 * (ComparisonCard, ComparisonDetailPage, ComparisonHistorySection) so the
 * same comparison can never be amber on one screen and blue on another.
 */

import type { BadgeProps } from '@/shared/ui/ui/badge';
import type { ComparisonStatus } from './types';

export const COMPARISON_STATUS_LABELS: Record<ComparisonStatus, string> = {
  PENDING: 'Pending',
  IN_PROGRESS: 'In progress',
  COMPLETED: 'Completed',
  FAILED: 'Failed',
};

export const COMPARISON_STATUS_VARIANT: Record<ComparisonStatus, NonNullable<BadgeProps['variant']>> = {
  PENDING: 'neutral',
  IN_PROGRESS: 'info',
  COMPLETED: 'success',
  FAILED: 'critical',
};

/**
 * Fallback-safe accessors (review r3): comparison responses are not Zod-validated, so a
 * backend status the maps lag behind reaches the pills at runtime — a direct Record lookup
 * rendered a blank badge. Surfaces use these instead of indexing the maps.
 */
export function comparisonStatusLabel(status: string): string {
  return COMPARISON_STATUS_LABELS[status as ComparisonStatus] ?? status;
}

export function comparisonStatusVariant(status: string): NonNullable<BadgeProps['variant']> {
  return COMPARISON_STATUS_VARIANT[status as ComparisonStatus] ?? 'neutral';
}

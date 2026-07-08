/**
 * Shared comparison status → pill mapping: every surface (ComparisonCard,
 * ComparisonDetailPage, ComparisonHistorySection) must speak the same
 * monitoring language — in-progress is blue 'info' with a human label,
 * never an amber problem or a raw enum string.
 */

import { describe, expect, it } from 'vitest';
import {
  COMPARISON_STATUS_LABELS,
  COMPARISON_STATUS_VARIANT,
} from './comparisonStatus';

const ALL_STATUSES = ['PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED'] as const;

describe('comparisonStatus shared mapping', () => {
  it.each(ALL_STATUSES)('provides a human label for %s', (status) => {
    expect(COMPARISON_STATUS_LABELS[status]).toBeDefined();
    expect(COMPARISON_STATUS_LABELS[status]).not.toBe(status);
  });

  it('maps in-progress to blue info, matching the batch language', () => {
    expect(COMPARISON_STATUS_VARIANT.IN_PROGRESS).toBe('info');
  });

  it('keeps terminal semantics: COMPLETED success, FAILED critical, PENDING neutral', () => {
    expect(COMPARISON_STATUS_VARIANT.COMPLETED).toBe('success');
    expect(COMPARISON_STATUS_VARIANT.FAILED).toBe('critical');
    expect(COMPARISON_STATUS_VARIANT.PENDING).toBe('neutral');
  });
});

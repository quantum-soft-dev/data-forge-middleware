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
  comparisonStatusLabel,
  comparisonStatusVariant,
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

  it('falls back to the raw status on a neutral pill for values the map lags behind', () => {
    // Comparison responses are not Zod-validated: a backend status added first
    // (e.g. CANCELLED) reaches the pills at runtime. The direct Record lookup
    // rendered a BLANK badge; the accessor helpers must degrade readably.
    expect(comparisonStatusLabel('CANCELLED')).toBe('CANCELLED');
    expect(comparisonStatusVariant('CANCELLED')).toBe('neutral');
  });

  it('accessor helpers agree with the maps for known statuses', () => {
    for (const status of ALL_STATUSES) {
      expect(comparisonStatusLabel(status)).toBe(COMPARISON_STATUS_LABELS[status]);
      expect(comparisonStatusVariant(status)).toBe(COMPARISON_STATUS_VARIANT[status]);
    }
  });
});

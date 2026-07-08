/**
 * Shared batch status mapping must cover every terminal backend status
 * (BatchStatus.java: COMPLETED, COMPLETED_WITH_WARNINGS, IN_PROGRESS,
 * FAILED, NOT_COMPLETED, CANCELLED) so no surface falls back to a raw
 * enum string on a neutral pill.
 */

import { describe, expect, it } from 'vitest';
import { STATUS_LABELS, STATUS_VARIANT } from './batchStatus';

const ALL_BACKEND_STATUSES = [
  'COMPLETED',
  'COMPLETED_WITH_WARNINGS',
  'IN_PROGRESS',
  'FAILED',
  'NOT_COMPLETED',
  'CANCELLED',
] as const;

describe('batchStatus shared mapping', () => {
  it.each(ALL_BACKEND_STATUSES)('provides a label for %s', (status) => {
    expect(STATUS_LABELS[status]).toBeDefined();
  });

  it.each(ALL_BACKEND_STATUSES)('provides a pill variant for %s', (status) => {
    expect(STATUS_VARIANT[status]).toBeDefined();
  });

  it('keeps failure semantics: FAILED is critical', () => {
    expect(STATUS_VARIANT.FAILED).toBe('critical');
  });

  it('marks abandoned and cancelled sessions as stalled, not neutral', () => {
    expect(STATUS_VARIANT.NOT_COMPLETED).toBe('stalled');
    expect(STATUS_VARIANT.CANCELLED).toBe('stalled');
  });
});

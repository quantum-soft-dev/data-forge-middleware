import { describe, it, expect } from 'vitest';
import { describeRebuildOutcome } from './rebuildOutcome';

describe('describeRebuildOutcome (#186)', () => {
  it('gives every known outcome a label and a severity', () => {
    expect(describeRebuildOutcome('COMPLETED')).toEqual({ label: 'Rebuilt', severity: 'healthy' });
    expect(describeRebuildOutcome('FAILED')).toEqual({
      label: 'Rebuild failed',
      severity: 'critical',
    });
    expect(describeRebuildOutcome('FRAME_UNAVAILABLE')).toEqual({
      label: 'Rebuild could not start',
      severity: 'critical',
    });
    expect(describeRebuildOutcome('DEFERRED')).toEqual({
      label: 'Rebuild deferred',
      severity: 'elevated',
    });
  });

  it('renders an outcome it has never heard of instead of dropping it', () => {
    // The Zod schema keeps this field a plain string on purpose (the `mode` precedent, review r3
    // of 023): a strict enum would reject the whole sync-state payload over one new value, which
    // would blank the entire Delta Sync tab. So an unknown verdict must still reach the operator.
    expect(describeRebuildOutcome('SOMETHING_NEW')).toEqual({
      label: 'Rebuild: SOMETHING_NEW',
      severity: 'elevated',
    });
  });

  it('has nothing to say when no rebuild has finished', () => {
    expect(describeRebuildOutcome(null)).toBeNull();
    expect(describeRebuildOutcome('')).toBeNull();
  });
});

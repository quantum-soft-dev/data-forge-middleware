import { describe, it, expect } from 'vitest';
import { describeRebuildOutcome, isRebuildOutcomeSuperseded } from './rebuildOutcome';

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

describe('isRebuildOutcomeSuperseded (#186)', () => {
  it('is true when a checkpoint was built after the verdict', () => {
    // Only a forced rebuild writes a verdict, so without this a FAILED one is a permanent critical
    // chip that outlives every nightly build that has since succeeded.
    expect(
      isRebuildOutcomeSuperseded({
        lastCheckpointAt: '2026-07-05T12:00:00Z',
        lastRebuildOutcomeAt: '2026-07-05T11:00:00Z',
      }),
    ).toBe(true);
  });

  it('is false while nothing has succeeded since', () => {
    expect(
      isRebuildOutcomeSuperseded({
        lastCheckpointAt: '2026-07-05T10:00:00Z',
        lastRebuildOutcomeAt: '2026-07-05T11:00:00Z',
      }),
    ).toBe(false);
  });

  it('is false when either timestamp is missing', () => {
    expect(
      isRebuildOutcomeSuperseded({ lastCheckpointAt: null, lastRebuildOutcomeAt: '2026-07-05T11:00:00Z' }),
    ).toBe(false);
    expect(
      isRebuildOutcomeSuperseded({ lastCheckpointAt: '2026-07-05T11:00:00Z', lastRebuildOutcomeAt: null }),
    ).toBe(false);
  });
});

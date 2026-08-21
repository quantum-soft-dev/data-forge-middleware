import { describe, it, expect } from 'vitest';
import { describeCheckpointBuildAbort } from './checkpointBuildAbort';

describe('describeCheckpointBuildAbort (issue #224)', () => {
  it('returns null when no abort is on record', () => {
    expect(describeCheckpointBuildAbort(null)).toBeNull();
    expect(describeCheckpointBuildAbort(undefined)).toBeNull();
    expect(describeCheckpointBuildAbort('')).toBeNull();
  });

  it('names the known reasons', () => {
    expect(describeCheckpointBuildAbort('FOLD_TOO_LARGE')).toEqual({
      label: 'Fold too large',
      severity: 'critical',
    });
    expect(describeCheckpointBuildAbort('DEFERRED')).toEqual({
      label: 'Build deferred',
      severity: 'elevated',
    });
  });

  it('degrades an unknown value rather than throwing', () => {
    // The sync-state payload drives the whole tab: a value added on the server must become an
    // unrecognised chip, not a blank page (the lastRebuildOutcome / mode precedent).
    expect(describeCheckpointBuildAbort('SOMETHING_NEW')).toEqual({
      label: 'Build: SOMETHING_NEW',
      severity: 'elevated',
    });
  });
});

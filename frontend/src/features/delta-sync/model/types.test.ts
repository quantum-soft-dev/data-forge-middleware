import { describe, it, expect } from 'vitest';
import { deltaSegmentSchema, deltaSyncStateSchema } from './types';

describe('deltaSegmentSchema (review r3)', () => {
  it('tolerates a session mode outside the known set instead of rejecting the whole array', () => {
    // The backend field is a free-form String (proto SessionMode.name() pass-through): a newer
    // client enum value (e.g. 'UNRECOGNIZED') or a mode added server-side first must not make
    // deltaSegmentSchema.array().parse throw for the entire segments response — that silently
    // blanks the throughput bars and the Recent segments card.
    const rows = [
      { firstSeq: 1, lastSeq: 100, recordCount: 100, mode: 'DELTA', createdAt: '2026-07-05T10:00:00Z' },
      { firstSeq: 101, lastSeq: 120, recordCount: 20, mode: 'UNRECOGNIZED', createdAt: '2026-07-05T11:00:00Z' },
    ];

    const parsed = deltaSegmentSchema.array().parse(rows);

    expect(parsed).toHaveLength(2);
    expect(parsed[1].mode).toBe('UNRECOGNIZED');
  });
});

describe('deltaSyncStateSchema.nextCheckpointBuildAt (issue #213)', () => {
  const base = {
    lastAppliedSeq: 1_155,
    lastCheckpointSeq: 0,
    lastCheckpointAt: null,
    schemaVersion: 1,
    updatedAt: '2026-07-05T12:00:00Z',
    rebaselineRequested: false,
    rebuildRequested: false,
  };

  it('carries the moment the scheduled build next runs', () => {
    expect(deltaSyncStateSchema.parse({ ...base, nextCheckpointBuildAt: '2026-07-06T02:00:00Z' })
      .nextCheckpointBuildAt).toBe('2026-07-06T02:00:00Z');
  });

  it('defaults to null, so an older backend still parses and the tab still renders', () => {
    expect(deltaSyncStateSchema.parse(base).nextCheckpointBuildAt).toBeNull();
    expect(deltaSyncStateSchema.parse({ ...base, nextCheckpointBuildAt: null })
      .nextCheckpointBuildAt).toBeNull();
  });
});

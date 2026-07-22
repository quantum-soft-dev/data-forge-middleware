import { describe, it, expect } from 'vitest';
import { deltaSegmentSchema } from './types';

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

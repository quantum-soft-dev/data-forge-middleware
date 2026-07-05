import { describe, it, expect } from 'vitest';
import {
  computeLag,
  formatLagShort,
  getSyncSeverity,
  isStalled,
  lagTrackPercent,
} from './severity';
import { deltaSyncStateSchema, deltaSyncHealthSchema, deltaSegmentSchema } from './types';

const NOW = new Date('2026-07-05T12:00:00Z');

describe('computeLag', () => {
  it('is applied minus checkpoint, floored at zero', () => {
    expect(computeLag({ lastAppliedSeq: 4821, lastCheckpointSeq: 3200 })).toBe(1621);
    expect(computeLag({ lastAppliedSeq: 100, lastCheckpointSeq: 100 })).toBe(0);
    expect(computeLag({ lastAppliedSeq: 50, lastCheckpointSeq: 100 })).toBe(0);
  });
});

describe('getSyncSeverity', () => {
  const fresh = '2026-07-05T11:59:00Z';

  it('applies the 1,000 / 10,000 thresholds', () => {
    expect(getSyncSeverity(0, fresh, NOW)).toBe('healthy');
    expect(getSyncSeverity(999, fresh, NOW)).toBe('healthy');
    expect(getSyncSeverity(1_000, fresh, NOW)).toBe('elevated');
    expect(getSyncSeverity(10_000, fresh, NOW)).toBe('elevated');
    expect(getSyncSeverity(10_001, fresh, NOW)).toBe('critical');
  });

  it('stalled (updatedAt > 24h) overrides the lag color', () => {
    const stale = '2026-07-04T11:00:00Z'; // 25h before NOW
    expect(getSyncSeverity(12, stale, NOW)).toBe('stalled');
    expect(getSyncSeverity(50_000, stale, NOW)).toBe('stalled');
    expect(isStalled(stale, NOW)).toBe(true);
    expect(isStalled(fresh, NOW)).toBe(false);
  });
});

describe('lagTrackPercent (sqrt scale, max 20k)', () => {
  it('places the thresholds at ~22.4% and ~70.7%', () => {
    expect(lagTrackPercent(1_000)).toBeCloseTo(22.36, 1);
    expect(lagTrackPercent(10_000)).toBeCloseTo(70.71, 1);
  });

  it('clamps to the track', () => {
    expect(lagTrackPercent(0)).toBe(0);
    expect(lagTrackPercent(-5)).toBe(0);
    expect(lagTrackPercent(20_000)).toBe(100);
    expect(lagTrackPercent(1_000_000)).toBe(100);
  });
});

describe('formatLagShort', () => {
  it('keeps small lags verbatim and abbreviates thousands', () => {
    expect(formatLagShort(12)).toBe('12');
    expect(formatLagShort(999)).toBe('999');
    expect(formatLagShort(2_300)).toBe('2.3k');
    expect(formatLagShort(18_200)).toBe('18.2k');
  });
});

describe('DTO schemas', () => {
  it('parses a sync-state payload', () => {
    const parsed = deltaSyncStateSchema.parse({
      lastAppliedSeq: 4821,
      lastCheckpointSeq: 3200,
      lastCheckpointAt: '2026-07-05T10:00:00Z',
      schemaVersion: 12,
      updatedAt: '2026-07-05T12:30:00Z',
      rebaselineRequested: false,
      rebuildRequested: true,
    });
    expect(parsed.rebuildRequested).toBe(true);
  });

  it('parses health entries with null fields for never-connected sites', () => {
    const parsed = deltaSyncHealthSchema.parse({
      siteId: 'abc',
      hasSyncState: false,
      lastAppliedSeq: null,
      lastCheckpointSeq: null,
      updatedAt: null,
    });
    expect(parsed.hasSyncState).toBe(false);
  });

  it('rejects unknown segment modes', () => {
    expect(() =>
      deltaSegmentSchema.parse({
        firstSeq: 1,
        lastSeq: 2,
        recordCount: 2,
        mode: 'WAT',
        createdAt: '2026-07-05T12:00:00Z',
      }),
    ).toThrow();
  });
});

import { describe, it, expect } from 'vitest';
import {
  computeLag,
  formatLagShort,
  getSyncSeverity,
  getSyncStatus,
  hasCheckpoint,
  isStalled,
  lagTrackPercent,
  syncStatusTone,
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

  it('tolerates unknown segment modes (decision changed in review r3)', () => {
    // The backend field is a free-form String: a strict enum rejected the WHOLE segments
    // array over one unknown value. Unknown modes now pass through and render neutrally.
    const parsed = deltaSegmentSchema.parse({
      firstSeq: 1,
      lastSeq: 2,
      recordCount: 2,
      mode: 'WAT',
      createdAt: '2026-07-05T12:00:00Z',
    });
    expect(parsed.mode).toBe('WAT');
  });
});

describe('getSyncStatus (issue #213)', () => {
  const fresh = '2026-07-05T11:59:00Z';
  const stale = '2026-07-04T11:00:00Z'; // 25h before NOW

  it('reads a site with no checkpoint as waiting for one, not as a backlog', () => {
    // The QA evidence: a FULL_SNAPSHOT of 1,155 records committed minutes ago, the first scheduled
    // build hours away. The lag verdict alone called that "Elevated".
    expect(
      getSyncStatus({ lastAppliedSeq: 1_155, lastCheckpointSeq: 0, updatedAt: fresh }, NOW),
    ).toBe('awaiting-first-checkpoint');
    expect(getSyncSeverity(1_155, fresh, NOW)).toBe('elevated');
  });

  it('reads a null checkpoint pointer (bulk health) the same way', () => {
    expect(
      getSyncStatus({ lastAppliedSeq: 40, lastCheckpointSeq: null, updatedAt: fresh }, NOW),
    ).toBe('awaiting-first-checkpoint');
  });

  it('is the ordinary lag verdict once a checkpoint exists', () => {
    expect(
      getSyncStatus({ lastAppliedSeq: 4_821, lastCheckpointSeq: 4_809, updatedAt: fresh }, NOW),
    ).toBe('healthy');
    expect(
      getSyncStatus({ lastAppliedSeq: 20_000, lastCheckpointSeq: 1, updatedAt: fresh }, NOW),
    ).toBe('critical');
  });

  it('still says stalled when the client itself has gone quiet', () => {
    // Stalled is about the client, not the checkpoint: a site that stopped talking a day ago is a
    // more actionable statement than "its first checkpoint has not been built".
    expect(
      getSyncStatus({ lastAppliedSeq: 1_155, lastCheckpointSeq: 0, updatedAt: stale }, NOW),
    ).toBe('stalled');
  });

  it('does not claim a site with nothing applied is waiting for a build (review r1)', () => {
    // An all-zero row is what a wipe leaves and what requestRebaseline creates for a client that
    // never connected. CheckpointScheduler visits the union of "has segments" and "has an
    // unmaterialized checkpoint row", and such a site is on neither list — so promising it a
    // scheduled build would be a promise nothing keeps. There is also nothing waiting.
    expect(
      getSyncStatus({ lastAppliedSeq: 0, lastCheckpointSeq: 0, updatedAt: fresh }, NOW),
    ).toBe('healthy');
  });

  it('hasCheckpoint reads zero as "none", the way the backend does', () => {
    // last_checkpoint_seq 0 is the initial value and what a wipe and a re-baseline reset to;
    // CheckpointService applies the same test before seeding from a frame.
    expect(hasCheckpoint({ lastCheckpointSeq: 0 })).toBe(false);
    expect(hasCheckpoint({ lastCheckpointSeq: null })).toBe(false);
    expect(hasCheckpoint({ lastCheckpointSeq: 1 })).toBe(true);
  });
});

describe('syncStatusTone', () => {
  it('paints the wait neutral, so it cannot read as an alarm', () => {
    const tone = syncStatusTone('awaiting-first-checkpoint');
    expect(tone.label).toBe('No checkpoint yet');
    expect(tone).not.toEqual(syncStatusTone('elevated'));
    expect(tone).not.toEqual(syncStatusTone('critical'));
  });

  it('keeps the severity palette for every real verdict', () => {
    expect(syncStatusTone('healthy').label).toBe('Healthy');
    expect(syncStatusTone('elevated').label).toBe('Elevated');
    expect(syncStatusTone('critical').label).toBe('Critical');
    expect(syncStatusTone('stalled').label).toBe('Stalled');
  });
});

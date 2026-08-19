import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { SyncStateShell } from './SyncStateShell'
import type { DeltaSyncState } from '../model/types'

const NOW = new Date('2026-07-05T12:00:00Z')

const baseState: DeltaSyncState = {
  lastAppliedSeq: 4821,
  lastCheckpointSeq: 4809,
  lastCheckpointAt: '2026-07-05T09:00:00Z',
  schemaVersion: 12,
  updatedAt: '2026-07-05T11:59:00Z',
  rebaselineRequested: false,
  rebuildRequested: false,
}

describe('SyncStateShell (F5)', () => {
  it('renders a Healthy chip, formatted headline and seq labels for a small lag', () => {
    render(<SyncStateShell state={baseState} now={NOW} />)

    expect(screen.getByTestId('severity-chip')).toHaveTextContent('Healthy')
    expect(screen.getByTestId('lag-headline')).toHaveTextContent('12')
    expect(screen.getByText(/Last checkpoint · seq 4,809/)).toBeInTheDocument()
    expect(screen.getByText(/Applied · seq 4,821/)).toBeInTheDocument()
    expect(screen.getByText('1k · watch')).toBeInTheDocument()
    expect(screen.getByText('10k · critical')).toBeInTheDocument()
    expect(screen.getByText(/live · updated/)).toBeInTheDocument()
  })

  it('renders Critical for lag over 10k with a proportional sqrt fill', () => {
    const state = { ...baseState, lastAppliedSeq: 23_200, lastCheckpointSeq: 5_000 }
    render(<SyncStateShell state={state} now={NOW} />)

    expect(screen.getByTestId('severity-chip')).toHaveTextContent('Critical')
    expect(screen.getByTestId('lag-headline')).toHaveTextContent('18,200')
    // sqrt(18200/20000)*100 ≈ 95.4%
    expect(screen.getByTestId('lag-fill').style.width).toMatch(/^95\.\d+%$/)
  })

  it('shows the stalled state when updatedAt is older than 24h', () => {
    const state = { ...baseState, updatedAt: '2026-07-04T10:00:00Z' }
    render(<SyncStateShell state={state} now={NOW} />)

    expect(screen.getByTestId('severity-chip')).toHaveTextContent('Stalled')
    expect(screen.getByText('Sync stalled?')).toBeInTheDocument()
    expect(screen.queryByText(/live · updated/)).not.toBeInTheDocument()
  })

  it('shows the Rebuild queued chip while rebuildRequested is set', () => {
    render(<SyncStateShell state={{ ...baseState, rebuildRequested: true }} now={NOW} />)
    expect(screen.getByText('Rebuild queued')).toBeInTheDocument()
  })

  it('shows the full-snapshot chip while rebaselineRequested is set', () => {
    render(<SyncStateShell state={{ ...baseState, rebaselineRequested: true }} now={NOW} />)
    expect(screen.getByText('Full snapshot scheduled on next connect')).toBeInTheDocument()
  })

  it('says "in progress", not "scheduled", once the snapshot is uploading (#84)', () => {
    // The request flag is only consumed at commit, so it is still set during the upload — the
    // header must not contradict the card below it.
    render(
      <SyncStateShell
        state={{ ...baseState, rebaselineRequested: true, snapshotInProgress: true }}
        now={NOW}
      />,
    )

    expect(screen.getByText('Full snapshot in progress')).toBeInTheDocument()
    expect(screen.queryByText('Full snapshot scheduled on next connect')).not.toBeInTheDocument()
  })

  it('reports the verdict of the last finished rebuild', () => {
    // Issue #186. Before this, a rebuild that never ran was indistinguishable from one that
    // worked: the chip vanished either way and the checkpoints did not change.
    render(
      <SyncStateShell
        state={{
          ...baseState,
          lastRebuildOutcome: 'DEFERRED',
          lastRebuildOutcomeAt: '2026-07-05T11:30:00Z',
          lastRebuildMessage: 'another checkpoint build held the fold budget',
        }}
        now={NOW}
      />,
    )

    expect(screen.getByText('Rebuild deferred')).toBeInTheDocument()
    expect(
      screen.getByText('another checkpoint build held the fold budget'),
    ).toBeInTheDocument()
  })

  it('shows no message for a rebuild that simply completed', () => {
    render(
      <SyncStateShell
        state={{
          ...baseState,
          lastRebuildOutcome: 'COMPLETED',
          lastRebuildOutcomeAt: '2026-07-05T11:30:00Z',
        }}
        now={NOW}
      />,
    )

    expect(screen.getByText('Rebuilt')).toBeInTheDocument()
    expect(screen.queryByTestId('rebuild-outcome-message')).not.toBeInTheDocument()
  })

  it('lets the queued chip win over the previous verdict', () => {
    // While rebuildRequested is up the verdict describes the PREVIOUS attempt, so showing both
    // would read as "queued, and it failed" for a rebuild that has not run yet.
    render(
      <SyncStateShell
        state={{
          ...baseState,
          rebuildRequested: true,
          lastRebuildOutcome: 'FAILED',
          lastRebuildOutcomeAt: '2026-07-05T11:30:00Z',
          lastRebuildMessage: 'boom',
        }}
        now={NOW}
      />,
    )

    expect(screen.getByText('Rebuild queued')).toBeInTheDocument()
    expect(screen.queryByText('Rebuild failed')).not.toBeInTheDocument()
    expect(screen.queryByText('boom')).not.toBeInTheDocument()
  })

  it('lets a verdict go quiet once a checkpoint has been built since', () => {
    // Raised in review: only a forced rebuild writes a verdict, so a FAILED one would otherwise
    // paint a critical chip for ever, outliving every nightly build that has since succeeded.
    const failedAndSuperseded = {
      ...baseState,
      lastCheckpointAt: '2026-07-05T11:45:00Z',
      lastRebuildOutcome: 'FAILED',
      lastRebuildOutcomeAt: '2026-07-05T11:00:00Z',
      lastRebuildMessage: 'boom',
    }
    const { rerender } = render(<SyncStateShell state={failedAndSuperseded} now={NOW} />)

    // Still reported, with its message — only the tone changes.
    expect(screen.getByText('Rebuild failed')).toBeInTheDocument()
    expect(screen.getByTestId('rebuild-outcome-message')).toBeInTheDocument()
    const quiet = screen.getByTestId('rebuild-outcome-chip').style.background

    rerender(
      <SyncStateShell
        state={{ ...failedAndSuperseded, lastCheckpointAt: '2026-07-05T10:00:00Z' }}
        now={NOW}
      />,
    )

    expect(screen.getByTestId('rebuild-outcome-chip').style.background).not.toBe(quiet)
  })

  it('renders schema version and checkpoint value', () => {
    render(<SyncStateShell state={baseState} now={NOW} />)
    expect(screen.getByText('v 12')).toBeInTheDocument()
    expect(screen.getByText('seq 4,809')).toBeInTheDocument()
  })

  describe('a site whose first checkpoint is not due yet (issue #213)', () => {
    // The QA run: a FULL_SNAPSHOT of 1,155 records committed at 15:45, the nightly build at 02:00.
    // The tab reported "Elevated — 1,155 records behind checkpoint", i.e. a designed wait as an alarm.
    const freshSite: DeltaSyncState = {
      ...baseState,
      lastAppliedSeq: 1_155,
      lastCheckpointSeq: 0,
      lastCheckpointAt: null,
      nextCheckpointBuildAt: '2026-07-06T02:00:00Z',
    }

    it('says no checkpoint exists yet instead of naming a severity', () => {
      render(<SyncStateShell state={freshSite} now={NOW} />)

      expect(screen.getByTestId('severity-chip')).toHaveTextContent('No checkpoint yet')
      expect(screen.getByTestId('severity-chip')).not.toHaveTextContent('Elevated')
    })

    it('keeps the number but says what it is waiting for', () => {
      render(<SyncStateShell state={freshSite} now={NOW} />)

      expect(screen.getByTestId('lag-headline')).toHaveTextContent('1,155')
      expect(screen.getByText(/awaiting the first checkpoint/)).toBeInTheDocument()
      expect(screen.queryByText(/records behind checkpoint/)).not.toBeInTheDocument()
    })

    it('replaces the threshold track with the moment the wait ends', () => {
      // The track is the alarm: its bands and its 1k/10k ticks are a scale of "how bad", and no
      // position on it is true for a site that has nothing to be behind.
      render(<SyncStateShell state={freshSite} now={NOW} />)

      expect(screen.queryByTestId('lag-fill')).not.toBeInTheDocument()
      expect(screen.queryByText('1k · watch')).not.toBeInTheDocument()
      expect(screen.getByTestId('first-checkpoint-note')).toHaveTextContent(
        // "Next", not "First": the value is the next occurrence of the cron, recomputed per
        // request, and the two stop coinciding the moment the first one passes (review r2).
        /next scheduled build/i,
      )
    })

    it('says what it means if the state outlives that build', () => {
      // The state cannot age itself out: nothing persisted says how long a site has been waiting,
      // so a first build that keeps failing would otherwise stay grey for ever (review r1).
      render(<SyncStateShell state={freshSite} now={NOW} />)

      expect(screen.getByTestId('first-checkpoint-note')).toHaveTextContent(
        /still missing a day later/i,
      )
    })

    it('does not promise a build to a site that has applied nothing', () => {
      // An all-zero row (a wipe, or a re-baseline requested for a client that never connected) is
      // on neither of the scheduler's work lists, so no build is coming — and nothing is waiting.
      render(
        <SyncStateShell
          state={{ ...freshSite, lastAppliedSeq: 0 }}
          now={NOW}
        />,
      )

      expect(screen.queryByTestId('first-checkpoint-note')).not.toBeInTheDocument()
      expect(screen.getByTestId('severity-chip')).toHaveTextContent('Healthy')
    })

    it('says only that the build is scheduled when the schedule names no next run', () => {
      // delta.checkpoint.cron can be disabled; promising a time the payload does not carry would
      // be the same class of lie this ticket is about.
      render(
        <SyncStateShell state={{ ...freshSite, nextCheckpointBuildAt: null }} now={NOW} />,
      )

      expect(screen.getByTestId('first-checkpoint-note')).toBeInTheDocument()
      expect(screen.getByTestId('first-checkpoint-note')).not.toHaveTextContent(
        /next scheduled build ·/i,
      )
    })

    it('goes back to the lag verdict as soon as a checkpoint exists', () => {
      render(
        <SyncStateShell
          state={{ ...freshSite, lastCheckpointSeq: 1, lastCheckpointAt: '2026-07-05T02:00:00Z' }}
          now={NOW}
        />,
      )

      expect(screen.getByTestId('severity-chip')).toHaveTextContent('Elevated')
      expect(screen.getByTestId('lag-fill')).toBeInTheDocument()
      expect(screen.queryByTestId('first-checkpoint-note')).not.toBeInTheDocument()
    })

    it('lets a stalled client win over the pending checkpoint', () => {
      render(
        <SyncStateShell state={{ ...freshSite, updatedAt: '2026-07-04T10:00:00Z' }} now={NOW} />,
      )

      expect(screen.getByTestId('severity-chip')).toHaveTextContent('Stalled')
    })
  })
})

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

  it('renders schema version and checkpoint value', () => {
    render(<SyncStateShell state={baseState} now={NOW} />)
    expect(screen.getByText('v 12')).toBeInTheDocument()
    expect(screen.getByText('seq 4,809')).toBeInTheDocument()
  })
})

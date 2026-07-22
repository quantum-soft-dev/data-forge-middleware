import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { SyncHealthPill } from './SyncHealthPill'
import type { DeltaSyncHealth } from '../model/types'

const NOW = new Date('2026-07-05T12:00:00Z')

function health(partial: Partial<DeltaSyncHealth>): DeltaSyncHealth {
  return {
    siteId: 'site-1',
    hasSyncState: true,
    lastAppliedSeq: 100,
    lastCheckpointSeq: 88,
    updatedAt: '2026-07-05T11:50:00Z',
    ...partial,
  }
}

describe('SyncHealthPill (F11)', () => {
  it('renders "Synced · lag N" for healthy sites', () => {
    render(
      <SyncHealthPill clientApiVersion="V2" health={health({})} isLoading={false} now={NOW} />,
    )
    expect(screen.getByTestId('sync-health-pill')).toHaveTextContent('Synced · lag 12')
  })

  it('renders "Lag 2.3k" (amber) for elevated lag', () => {
    render(
      <SyncHealthPill
        clientApiVersion="V2"
        health={health({ lastAppliedSeq: 2_388, lastCheckpointSeq: 88 })}
        isLoading={false}
        now={NOW}
      />,
    )
    expect(screen.getByTestId('sync-health-pill')).toHaveTextContent('Lag 2.3k')
  })

  it('renders "Lag 18.2k" (red) for critical lag', () => {
    render(
      <SyncHealthPill
        clientApiVersion="V2"
        health={health({ lastAppliedSeq: 18_288, lastCheckpointSeq: 88 })}
        isLoading={false}
        now={NOW}
      />,
    )
    expect(screen.getByTestId('sync-health-pill')).toHaveTextContent('Lag 18.2k')
  })

  it('renders "Stalled · N h" when updatedAt is older than 24h', () => {
    render(
      <SyncHealthPill
        clientApiVersion="V2"
        health={health({ updatedAt: '2026-07-04T10:00:00Z' })}
        isLoading={false}
        now={NOW}
      />,
    )
    expect(screen.getByTestId('sync-health-pill')).toHaveTextContent('Stalled · 26 h')
  })

  it('renders the grey "No sync yet" pill when the client never connected', () => {
    render(
      <SyncHealthPill
        clientApiVersion="V2"
        health={health({ hasSyncState: false, lastAppliedSeq: null, lastCheckpointSeq: null, updatedAt: null })}
        isLoading={false}
        now={NOW}
      />,
    )
    expect(screen.getByTestId('sync-health-pill')).toHaveTextContent('No sync yet')
  })

  it('renders muted "Snapshot uploads" text for V1 sites', () => {
    render(<SyncHealthPill clientApiVersion="V1" isLoading={false} now={NOW} />)
    expect(screen.getByTestId('sync-health-v1')).toHaveTextContent('Snapshot uploads')
  })

  it('renders nothing for V2 sites while the bulk data is loading (no skeleton)', () => {
    const { container } = render(<SyncHealthPill clientApiVersion="V2" isLoading={true} now={NOW} />)
    expect(container).toBeEmptyDOMElement()
  })
})

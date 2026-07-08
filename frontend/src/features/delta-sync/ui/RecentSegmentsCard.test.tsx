import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { RecentSegmentsCard } from './RecentSegmentsCard'
import type { DeltaSegment } from '../model/types'

const segments: DeltaSegment[] = [
  { firstSeq: 951, lastSeq: 1200, recordCount: 250, mode: 'CONTINUOUS', createdAt: '2026-07-05T10:00:00Z' },
  { firstSeq: 401, lastSeq: 950, recordCount: 550, mode: 'DELTA', createdAt: '2026-07-03T10:00:00Z' },
  { firstSeq: 1, lastSeq: 400, recordCount: 400, mode: 'FULL_SNAPSHOT', createdAt: '2026-07-01T10:00:00Z' },
]

describe('RecentSegmentsCard (F8)', () => {
  it('is collapsed by default and expands on header click', async () => {
    render(<RecentSegmentsCard segments={segments} />)

    expect(screen.queryByTestId('recent-segments-body')).not.toBeInTheDocument()
    expect(screen.getByText('Last 20 changelog segments · admin only')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /recent segments/i }))
    expect(screen.getByTestId('recent-segments-body')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /recent segments/i })).toHaveAttribute('aria-expanded', 'true')
  })

  it('renders seq ranges, record counts, mode chips and short dates', async () => {
    render(<RecentSegmentsCard segments={segments} />)
    await userEvent.click(screen.getByRole('button', { name: /recent segments/i }))

    expect(screen.getByText('951–1,200')).toBeInTheDocument()
    expect(screen.getByText('550')).toBeInTheDocument()
    expect(screen.getByText('DELTA')).toBeInTheDocument()
    expect(screen.getByText('CONTINUOUS')).toBeInTheDocument()
    expect(screen.getByText('FULL_SNAPSHOT')).toBeInTheDocument()
    // Short date format "MMM DD, HH:mm" — no year, no seconds (F1)
    expect(screen.getAllByText(/^[A-Z][a-z]{2} \d{2}, \d{2}:\d{2}$/).length).toBe(3)
  })

  it('renders a segment with an unknown mode on a neutral chip without crashing', async () => {
    const unknownMode = [
      { firstSeq: 1, lastSeq: 100, recordCount: 100, mode: 'UNRECOGNIZED', createdAt: '2026-07-05T10:00:00Z' },
    ] as DeltaSegment[]
    render(<RecentSegmentsCard segments={unknownMode} />)
    await userEvent.click(screen.getByRole('button', { name: /recent segments/i }))

    expect(screen.getByText('UNRECOGNIZED')).toBeInTheDocument()
  })

  it('keeps row keys unique for empty segments sharing a watermark', async () => {
    // An empty seal persists with lastSeq = firstSeq - 1; two of them at the same watermark
    // collided on the `${firstSeq}-${lastSeq}` key (React duplicate-key warning, merged rows).
    const emptySeals = [
      { firstSeq: 4821, lastSeq: 4820, recordCount: 0, mode: 'CONTINUOUS', createdAt: '2026-07-05T10:00:00Z' },
      { firstSeq: 4821, lastSeq: 4820, recordCount: 0, mode: 'CONTINUOUS', createdAt: '2026-07-05T10:05:00Z' },
    ] as DeltaSegment[]
    const warn = vi.spyOn(console, 'error').mockImplementation(() => {})
    render(<RecentSegmentsCard segments={emptySeals} />)
    await userEvent.click(screen.getByRole('button', { name: /recent segments/i }))

    expect(warn.mock.calls.flat().join(' ')).not.toContain('same key')
    warn.mockRestore()
  })

  it('shows an empty note when the site has no segments', async () => {
    render(<RecentSegmentsCard segments={[]} />)
    await userEvent.click(screen.getByRole('button', { name: /recent segments/i }))
    expect(screen.getByText('No segments yet.')).toBeInTheDocument()
  })

  it('mode chips use the monitoring palette', async () => {
    render(<RecentSegmentsCard segments={segments} />)
    await userEvent.click(screen.getByRole('button', { name: /recent segments/i }))

    expect(screen.getByText('DELTA')).toHaveStyle({ color: 'rgb(60, 130, 216)' })
    expect(screen.getByText('CONTINUOUS')).toHaveStyle({ color: 'rgb(21, 128, 61)' })
    expect(screen.getByText('FULL_SNAPSHOT')).toHaveStyle({ color: 'rgb(180, 83, 9)' })
  })
})

import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { renderHook } from '@testing-library/react'
import { ActivityCard } from './ActivityCard'
import { sparklinePoints, useLagHistory } from '../model/useLagHistory'
import type { DeltaSegment } from '../model/types'

function segment(firstSeq: number, records: number): DeltaSegment {
  return {
    firstSeq,
    lastSeq: firstSeq + records - 1,
    recordCount: records,
    mode: 'DELTA',
    createdAt: '2026-07-05T10:00:00Z',
  }
}

describe('ActivityCard (F6)', () => {
  it('renders the lag history caption and sparkline', () => {
    render(<ActivityCard lagSamples={[10, 20, 15]} status="healthy" showThroughput={false} />)

    expect(screen.getByText('Sampled on each poll · since page open')).toBeInTheDocument()
    expect(screen.getByTestId('lag-sparkline')).toBeInTheDocument()
  })

  it('hides the throughput panel for owners while P2 is pending', () => {
    render(<ActivityCard lagSamples={[10]} status="healthy" showThroughput={false} />)
    expect(screen.queryByText('Segment throughput')).not.toBeInTheDocument()
  })

  it('renders at most 16 throughput bars for admins', () => {
    const segments = Array.from({ length: 20 }, (_, i) => segment(i * 100 + 1, 50 + i))
    render(
      <ActivityCard lagSamples={[10]} status="healthy" segments={segments} showThroughput={true} />,
    )

    expect(screen.getByText('Segment throughput')).toBeInTheDocument()
    expect(screen.getByTestId('throughput-bars').children).toHaveLength(16)
  })

  it('shows an inline empty note when the admin has no segments yet', () => {
    render(<ActivityCard lagSamples={[]} status="healthy" segments={[]} showThroughput={true} />)
    expect(screen.getByText('No segments yet')).toBeInTheDocument()
  })
})

describe('sparklinePoints', () => {
  it('renders a flat line from a single sample (cold start, D6)', () => {
    const points = sparklinePoints([42], 100, 56)
    const [first, second] = points.split(' ')
    expect(first.split(',')[1]).toBe(second.split(',')[1])
    expect(second.split(',')[0]).toBe('100.0')
  })

  it('returns an empty string without samples', () => {
    expect(sparklinePoints([], 100, 56)).toBe('')
  })
})

describe('useLagHistory', () => {
  it('appends one sample per completed poll and ignores duplicates', () => {
    const { result, rerender } = renderHook(
      ({ lag, at }: { lag: number | null; at: number }) => useLagHistory(lag, at),
      { initialProps: { lag: 10 as number | null, at: 1000 } },
    )
    expect(result.current).toEqual([10])

    // Same dataUpdatedAt → no new sample
    rerender({ lag: 10, at: 1000 })
    expect(result.current).toEqual([10])

    // New poll → new sample
    rerender({ lag: 25, at: 2000 })
    expect(result.current).toEqual([10, 25])

    // Null lag (empty state) → ignored
    rerender({ lag: null, at: 3000 })
    expect(result.current).toEqual([10, 25])
  })
})

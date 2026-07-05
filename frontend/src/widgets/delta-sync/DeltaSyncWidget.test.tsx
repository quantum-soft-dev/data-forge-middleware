import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { DeltaSyncWidget } from './DeltaSyncWidget'
import { useDeltaSyncState } from '@/features/delta-sync/api/queries'
import type { DeltaSyncState } from '@/features/delta-sync/model/types'

vi.mock('@/features/delta-sync/api/queries', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/delta-sync/api/queries')>()
  return { ...actual, useDeltaSyncState: vi.fn() }
})

const mockedUseDeltaSyncState = vi.mocked(useDeltaSyncState)

const state: DeltaSyncState = {
  lastAppliedSeq: 4821,
  lastCheckpointSeq: 4809,
  lastCheckpointAt: '2026-07-05T09:00:00Z',
  schemaVersion: 12,
  updatedAt: new Date().toISOString(),
  rebaselineRequested: false,
  rebuildRequested: false,
}

function mockQuery(partial: { isLoading?: boolean; isError?: boolean; data?: DeltaSyncState | null }) {
  mockedUseDeltaSyncState.mockReturnValue({
    isLoading: partial.isLoading ?? false,
    isError: partial.isError ?? false,
    data: partial.data,
  } as ReturnType<typeof useDeltaSyncState>)
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('DeltaSyncWidget (F5)', () => {
  it('renders skeletons while loading', () => {
    mockQuery({ isLoading: true })
    render(<DeltaSyncWidget siteId="s1" admin={false} canManage={false} />)
    expect(screen.getByTestId('delta-sync-loading')).toBeInTheDocument()
  })

  it('renders the standard error block on failure', () => {
    mockQuery({ isError: true })
    render(<DeltaSyncWidget siteId="s1" admin={false} canManage={false} />)
    expect(screen.getByText(/failed to load sync state/i)).toBeInTheDocument()
  })

  it('replaces the whole tab with the empty state when the client never connected', () => {
    mockQuery({ data: null })
    render(<DeltaSyncWidget siteId="s1" admin={false} canManage={false} />)

    expect(screen.getByTestId('delta-sync-empty-state')).toBeInTheDocument()
    expect(screen.getByText('No sync activity yet')).toBeInTheDocument()
    expect(screen.queryByTestId('sync-state-shell')).not.toBeInTheDocument()
  })

  it('renders the sync state shell when data is present', () => {
    mockQuery({ data: state })
    render(<DeltaSyncWidget siteId="s1" admin={false} canManage={false} />)
    expect(screen.getByTestId('sync-state-shell')).toBeInTheDocument()
  })

  it('passes the admin scope to the sync-state query', () => {
    mockQuery({ data: state })
    render(<DeltaSyncWidget siteId="s1" admin={true} canManage={true} />)
    expect(mockedUseDeltaSyncState).toHaveBeenCalledWith('s1', { admin: true })
  })
})

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { DeltaSyncWidget } from './DeltaSyncWidget'
import userEvent from '@testing-library/user-event'
import { toast } from 'sonner'
import {
  useCancelRebaseline,
  useDeltaCheckpoints,
  useDeltaSegments,
  useDeltaSyncState,
  useRebuildCheckpoint,
  useRequestRebaseline,
} from '@/features/delta-sync/api/queries'
import type { DeltaSyncState } from '@/features/delta-sync/model/types'

vi.mock('@/features/delta-sync/api/queries', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/delta-sync/api/queries')>()
  return {
    ...actual,
    useDeltaSyncState: vi.fn(),
    useDeltaSegments: vi.fn(),
    useDeltaCheckpoints: vi.fn(),
    useRebuildCheckpoint: vi.fn(),
    useRequestRebaseline: vi.fn(),
    useCancelRebaseline: vi.fn(),
  }
})

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
}))

const mockedUseDeltaSyncState = vi.mocked(useDeltaSyncState)
const mockedUseDeltaSegments = vi.mocked(useDeltaSegments)
const mockedUseDeltaCheckpoints = vi.mocked(useDeltaCheckpoints)
const mockedUseRebuildCheckpoint = vi.mocked(useRebuildCheckpoint)
const mockedUseRequestRebaseline = vi.mocked(useRequestRebaseline)
const mockedUseCancelRebaseline = vi.mocked(useCancelRebaseline)
const rebuildMutate = vi.fn()
const rebaselineMutate = vi.fn()
const cancelRebaselineMutate = vi.fn()

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
    dataUpdatedAt: partial.data ? Date.now() : 0,
  } as ReturnType<typeof useDeltaSyncState>)
}

beforeEach(() => {
  vi.clearAllMocks()
  mockedUseDeltaSegments.mockReturnValue({
    data: undefined,
    isLoading: false,
    isError: false,
  } as ReturnType<typeof useDeltaSegments>)
  mockedUseDeltaCheckpoints.mockReturnValue({
    data: [],
    isLoading: false,
    isError: false,
  } as unknown as ReturnType<typeof useDeltaCheckpoints>)
  mockedUseRebuildCheckpoint.mockReturnValue({
    mutate: rebuildMutate,
    isPending: false,
  } as unknown as ReturnType<typeof useRebuildCheckpoint>)
  mockedUseRequestRebaseline.mockReturnValue({
    mutate: rebaselineMutate,
    isPending: false,
  } as unknown as ReturnType<typeof useRequestRebaseline>)
  mockedUseCancelRebaseline.mockReturnValue({
    mutate: cancelRebaselineMutate,
    isPending: false,
  } as unknown as ReturnType<typeof useCancelRebaseline>)
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

  it('surfaces a checkpoints query failure instead of a legitimate-looking empty list', () => {
    // A 500 on /checkpoints must not read as "No checkpoints yet." during an incident.
    mockQuery({ data: state })
    mockedUseDeltaCheckpoints.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
    } as unknown as ReturnType<typeof useDeltaCheckpoints>)

    render(<DeltaSyncWidget siteId="s1" admin={false} canManage={false} />)

    expect(screen.getByText(/failed to load checkpoints/i)).toBeInTheDocument()
    expect(screen.queryByText(/no checkpoints yet/i)).not.toBeInTheDocument()
  })

  it('surfaces a segments query failure instead of "No segments yet"', () => {
    mockQuery({ data: state })
    mockedUseDeltaSegments.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
    } as unknown as ReturnType<typeof useDeltaSegments>)

    render(<DeltaSyncWidget siteId="s1" admin={true} canManage={true} />)

    expect(screen.getByText(/failed to load segments/i)).toBeInTheDocument()
    expect(screen.queryByText(/no segments yet/i)).not.toBeInTheDocument()
  })

  it('holds the rebuild and re-baseline triggers while their mutation is in flight', () => {
    // The buttons were gated only by the server-side flags, leaving a double-submit
    // window until the invalidated sync-state refetch lands.
    mockQuery({ data: state })
    mockedUseRebuildCheckpoint.mockReturnValue({
      mutate: rebuildMutate,
      isPending: true,
    } as unknown as ReturnType<typeof useRebuildCheckpoint>)
    mockedUseRequestRebaseline.mockReturnValue({
      mutate: rebaselineMutate,
      isPending: true,
    } as unknown as ReturnType<typeof useRequestRebaseline>)

    render(<DeltaSyncWidget siteId="s1" admin={true} canManage={true} />)

    expect(screen.getByRole('button', { name: /rebuild checkpoint now/i })).toBeDisabled()
    // The re-baseline button is replaced by the pending pill while the request is in flight.
    expect(screen.queryByRole('button', { name: /request full re-baseline/i })).not.toBeInTheDocument()
    expect(screen.getByText(/full snapshot scheduled on next connect/i)).toBeInTheDocument()
  })
})

describe('DeltaSyncWidget actions (F9)', () => {
  it('confirms the rebuild via AlertDialog and toasts on success', async () => {
    mockQuery({ data: state })
    rebuildMutate.mockImplementation((_vars, options) => options?.onSuccess?.())
    render(<DeltaSyncWidget siteId="s1" admin={true} canManage={true} />)

    await userEvent.click(screen.getByRole('button', { name: /rebuild checkpoint now/i }))
    expect(screen.getByText('Rebuild checkpoint now?')).toBeInTheDocument()
    expect(screen.getByText(/heavy operation on large tables/i)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Rebuild now' }))
    expect(rebuildMutate).toHaveBeenCalled()
    expect(toast.success).toHaveBeenCalledWith('Checkpoint rebuild scheduled')
  })

  it('cancelling the rebuild dialog does not fire the mutation', async () => {
    mockQuery({ data: state })
    render(<DeltaSyncWidget siteId="s1" admin={true} canManage={true} />)

    await userEvent.click(screen.getByRole('button', { name: /rebuild checkpoint now/i }))
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(rebuildMutate).not.toHaveBeenCalled()
  })

  it('confirms the re-baseline via AlertDialog with the red warning panel', async () => {
    mockQuery({ data: state })
    rebaselineMutate.mockImplementation((_vars, options) => options?.onSuccess?.())
    render(<DeltaSyncWidget siteId="s1" admin={false} canManage={false} />)

    await userEvent.click(screen.getByRole('button', { name: 'Request full re-baseline' }))
    expect(screen.getByText('Request full re-baseline?')).toBeInTheDocument()
    // The dialog must state the cost: the whole dataset is re-sent (#84).
    expect(screen.getByText(/re-uploads the entire dataset/i)).toBeInTheDocument()
    expect(screen.getByText(/take the request back until the client starts/i)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Request re-baseline' }))
    expect(rebaselineMutate).toHaveBeenCalled()
    expect(toast.success).toHaveBeenCalledWith('Full re-baseline requested')
  })

  it('uses the standard error toast when a mutation fails', async () => {
    mockQuery({ data: state })
    rebaselineMutate.mockImplementation((_vars, options) => options?.onError?.(new Error('500')))
    render(<DeltaSyncWidget siteId="s1" admin={false} canManage={false} />)

    await userEvent.click(screen.getByRole('button', { name: 'Request full re-baseline' }))
    await userEvent.click(screen.getByRole('button', { name: 'Request re-baseline' }))
    expect(toast.error).toHaveBeenCalledWith('Something went wrong. Please try again.')
  })

  it('drives the pending pill from the persistent rebaselineRequested flag', () => {
    mockQuery({ data: { ...state, rebaselineRequested: true } })
    render(<DeltaSyncWidget siteId="s1" admin={false} canManage={false} />)

    expect(screen.queryByRole('button', { name: 'Request full re-baseline' })).not.toBeInTheDocument()
    // Pill appears in both the rebaseline card and the lag-card header
    expect(screen.getAllByText('Full snapshot scheduled on next connect').length).toBeGreaterThanOrEqual(1)
  })
})

describe('DeltaSyncWidget re-baseline cancellation (#84)', () => {
  it('confirms the cancellation via AlertDialog and toasts on success', async () => {
    mockQuery({ data: { ...state, rebaselineRequested: true } })
    cancelRebaselineMutate.mockImplementation((_vars, options) => options?.onSuccess?.('cancelled'))
    render(<DeltaSyncWidget siteId="s1" admin={false} canManage={false} />)

    await userEvent.click(screen.getByRole('button', { name: /cancel request/i }))
    expect(screen.getByText('Cancel the re-baseline request?')).toBeInTheDocument()
    expect(screen.getByText(/watermark and checkpoints are left as they are/i)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Cancel re-baseline' }))
    expect(cancelRebaselineMutate).toHaveBeenCalled()
    expect(toast.success).toHaveBeenCalledWith('Re-baseline request cancelled')
  })

  it('warns instead of confirming while the site is still ingesting (#84)', async () => {
    // The flag outlives the whole FULL_SNAPSHOT session (it is consumed at commit), so clearing it
    // does not reach a snapshot in flight — a success toast would claim the re-upload was averted.
    mockQuery({ data: { ...state, rebaselineRequested: true } })
    cancelRebaselineMutate.mockImplementation((_vars, options) =>
      options?.onSuccess?.('session-in-progress'),
    )
    render(<DeltaSyncWidget siteId="s1" admin={false} canManage={false} />)

    await userEvent.click(screen.getByRole('button', { name: /cancel request/i }))
    await userEvent.click(screen.getByRole('button', { name: 'Cancel re-baseline' }))
    expect(toast.warning).toHaveBeenCalledWith(
      'Request cleared, but the site is already ingesting — a full snapshot in flight still runs to completion',
    )
    expect(toast.success).not.toHaveBeenCalled()
  })

  it('warns without naming a cause when nothing was pending anymore (#84)', async () => {
    // `not-requested` has several causes (snapshot already committed, another operator, stale
    // pill), so the message must not assert one of them.
    mockQuery({ data: { ...state, rebaselineRequested: true } })
    cancelRebaselineMutate.mockImplementation((_vars, options) => options?.onSuccess?.('not-requested'))
    render(<DeltaSyncWidget siteId="s1" admin={false} canManage={false} />)

    await userEvent.click(screen.getByRole('button', { name: /cancel request/i }))
    await userEvent.click(screen.getByRole('button', { name: 'Cancel re-baseline' }))
    expect(toast.warning).toHaveBeenCalledWith('No re-baseline was pending — nothing to cancel')
    expect(toast.success).not.toHaveBeenCalled()
  })

  it('keeps the request when the dialog is dismissed', async () => {
    mockQuery({ data: { ...state, rebaselineRequested: true } })
    render(<DeltaSyncWidget siteId="s1" admin={false} canManage={false} />)

    await userEvent.click(screen.getByRole('button', { name: /cancel request/i }))
    await userEvent.click(screen.getByRole('button', { name: 'Keep request' }))
    expect(cancelRebaselineMutate).not.toHaveBeenCalled()
  })

  it('uses the standard error toast when the cancellation fails', async () => {
    mockQuery({ data: { ...state, rebaselineRequested: true } })
    cancelRebaselineMutate.mockImplementation((_vars, options) => options?.onError?.(new Error('500')))
    render(<DeltaSyncWidget siteId="s1" admin={false} canManage={false} />)

    await userEvent.click(screen.getByRole('button', { name: /cancel request/i }))
    await userEvent.click(screen.getByRole('button', { name: 'Cancel re-baseline' }))
    expect(toast.error).toHaveBeenCalledWith('Something went wrong. Please try again.')
  })

  it('passes the admin scope to the cancel mutation', () => {
    mockQuery({ data: { ...state, rebaselineRequested: true } })
    render(<DeltaSyncWidget siteId="s1" admin={true} canManage={true} />)
    expect(mockedUseCancelRebaseline).toHaveBeenCalledWith('s1', { admin: true })
  })

  it('holds the cancel button while the cancellation is in flight', () => {
    mockQuery({ data: { ...state, rebaselineRequested: true } })
    mockedUseCancelRebaseline.mockReturnValue({
      mutate: cancelRebaselineMutate,
      isPending: true,
    } as unknown as ReturnType<typeof useCancelRebaseline>)

    render(<DeltaSyncWidget siteId="s1" admin={false} canManage={false} />)

    expect(screen.getByRole('button', { name: /cancel request/i })).toBeDisabled()
  })
})

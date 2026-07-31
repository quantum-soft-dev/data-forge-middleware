/**
 * What the wipe mutation invalidates (#89).
 *
 * The Danger zone shares its page with the "Upload history" tab, so the caches the wipe empties
 * server-side are exactly the ones that must not survive it client-side.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useWipeSiteHistory } from './queries'
import { batchKeys } from '@/entities/batch/api/queries'
import * as api from './deltaSyncApi'

vi.mock('./deltaSyncApi', async (importOriginal) => ({
  ...(await importOriginal<typeof api>()),
  wipeSiteHistory: vi.fn(),
}))

const summary = {
  generation: 4,
  deletedBatches: 123,
  deletedSegments: 45,
  deletedCheckpoints: 12,
  deletedFiles: 678,
  deletedSqlGenerations: 9,
  deletedErrorLogs: 33,
  deletedBytes: 123456789,
  s3DeleteErrors: 0,
  baselineBatchDetached: false,
}

describe('useWipeSiteHistory (#89)', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    vi.clearAllMocks()
    vi.mocked(api.wipeSiteHistory).mockResolvedValue(summary)
  })

  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )

  it('invalidates the batch caches, which the wipe empties server-side', async () => {
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useWipeSiteHistory('s1', { admin: false }), { wrapper })
    result.current.mutate('store-01')

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // Without this the Upload history tab — the default tab of the same page — keeps listing
    // batches that no longer exist, each row leading to a 404 detail page.
    expect(invalidate).toHaveBeenCalledWith({ queryKey: batchKeys.all })
  })

  it('invalidates every delta-sync view of the site', async () => {
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useWipeSiteHistory('s1', { admin: false }), { wrapper })
    result.current.mutate('store-01')

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    const keys = invalidate.mock.calls.map(([arg]) => JSON.stringify(arg?.queryKey))
    expect(keys).toContain(JSON.stringify(['delta-sync', 'sync-state', 's1']))
    expect(keys).toContain(JSON.stringify(['delta-sync', 'checkpoints', 's1']))
    expect(keys).toContain(JSON.stringify(['delta-sync', 'segments', 's1']))
  })

  it('leaves the caches alone when the wipe is refused', async () => {
    vi.mocked(api.wipeSiteHistory).mockRejectedValue(
      new api.SiteHistoryWipeConflictError('session-in-progress'),
    )
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useWipeSiteHistory('s1', { admin: false }), { wrapper })
    result.current.mutate('store-01')

    await waitFor(() => expect(result.current.isError).toBe(true))

    // A 409 means nothing was deleted — refetching would only churn.
    expect(invalidate).not.toHaveBeenCalled()
  })
})

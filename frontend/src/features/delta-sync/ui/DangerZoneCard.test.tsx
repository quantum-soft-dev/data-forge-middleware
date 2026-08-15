import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { toast } from 'sonner'
import { DangerZoneCard } from './DangerZoneCard'
import { useWipeSiteHistory } from '../api/queries'
import type { SiteHistoryWipeResult } from '../model/types'

vi.mock('../api/queries', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/queries')>()
  return { ...actual, useWipeSiteHistory: vi.fn() }
})

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
}))

const SITE = 'store-01.example.com'
const mutate = vi.fn()

const summary = (overrides: Partial<SiteHistoryWipeResult> = {}): SiteHistoryWipeResult => ({
  generation: 4,
  deletedBatches: 3,
  deletedSegments: 1,
  deletedCheckpoints: 1,
  deletedFiles: 2,
  deletedSqlGenerations: 0,
  deletedErrorLogs: 0,
  deletedBytes: 10,
  s3DeleteErrors: 0,
  prefixesNotSwept: 0,
  baselineBatchDetached: false,
  ...overrides,
})

describe('DangerZoneCard (#122)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(useWipeSiteHistory).mockReturnValue({
      mutate,
      isPending: false,
    } as never)
  })

  async function confirmWipe() {
    render(<DangerZoneCard siteId="s1" siteName={SITE} scope={{ admin: false }} />)
    await userEvent.click(screen.getByRole('button', { name: 'Wipe history' }))
    await userEvent.type(screen.getByLabelText(/confirm the site name/i), SITE)
    await userEvent.click(screen.getByRole('button', { name: 'Wipe history' }))
  }

  it('tells the operator to repeat the wipe when a prefix was not swept', async () => {
    mutate.mockImplementation((_confirm: string, options: { onSuccess: (r: SiteHistoryWipeResult) => void }) => {
      options.onSuccess(summary({ prefixesNotSwept: 1 }))
    })

    await confirmWipe()

    expect(toast.warning).toHaveBeenCalledWith(
      expect.stringMatching(/repeat the wipe/i),
    )
    expect(toast.warning).not.toHaveBeenCalledWith(
      expect.stringMatching(/storage object\(s\) could not be deleted/i),
    )
  })

  it('still quotes the object count when deletes failed and every prefix was listed', async () => {
    mutate.mockImplementation((_confirm: string, options: { onSuccess: (r: SiteHistoryWipeResult) => void }) => {
      options.onSuccess(summary({ s3DeleteErrors: 2 }))
    })

    await confirmWipe()

    expect(toast.warning).toHaveBeenCalledWith(
      '2 storage object(s) could not be deleted and were left behind.',
    )
    expect(toast.warning).not.toHaveBeenCalledWith(
      expect.stringMatching(/repeat the wipe/i),
    )
  })
})

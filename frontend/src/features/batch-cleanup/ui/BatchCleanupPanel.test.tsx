import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BatchCleanupPanel } from './BatchCleanupPanel'
import * as siteQueries from '@/features/site-crud/model/queries'
import * as batchApi from '@/entities/batch/api/batchApi'

vi.mock('@/features/site-crud/model/queries', () => ({
  useAdminSites: vi.fn(),
}))

vi.mock('@/entities/batch/api/batchApi', () => ({
  runBatchCleanup: vi.fn(),
}))

const mockSites = [
  {
    id: 'site-1',
    accountId: 'account-1',
    domain: 'example.com',
    name: 'Example',
    isActive: true,
    retentionDays: 45,
    createdAt: '2025-01-01T00:00:00Z',
  },
  {
    id: 'site-2',
    accountId: 'account-1',
    domain: 'shop.example.com',
    name: 'Shop',
    isActive: true,
    retentionDays: 45,
    createdAt: '2025-01-01T00:00:00Z',
  },
]

describe('BatchCleanupPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(siteQueries.useAdminSites).mockReturnValue({
      data: mockSites,
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof siteQueries.useAdminSites>)
  })

  it('runs dry run cleanup with provided filters', async () => {
    vi.mocked(batchApi.runBatchCleanup).mockResolvedValue({
      candidates: 2,
      deletedBatches: 0,
      deletedFiles: 0,
      deletedBytes: 0,
      errors: [],
    })

    render(<BatchCleanupPanel accountId="account-1" />)

    const siteSelect = screen.getByLabelText('Site')
    await userEvent.selectOptions(siteSelect, 'site-2')

    await userEvent.type(screen.getByLabelText('Retention Days (override)'), '30')
    await userEvent.type(screen.getByLabelText('Older Than (override)'), '2025-01-15T10:30')

    const limitInput = screen.getByLabelText('Limit')
    await userEvent.clear(limitInput)
    await userEvent.type(limitInput, '500')

    await userEvent.click(screen.getByRole('button', { name: /run dry run/i }))

    await waitFor(() => {
      expect(batchApi.runBatchCleanup).toHaveBeenCalledWith({
        accountId: 'account-1',
        siteId: 'site-2',
        retentionDays: 30,
        olderThan: '2025-01-15T10:30:00',
        limit: 500,
        dryRun: true,
      })
    })

    expect(await screen.findByText('Candidates: 2')).toBeInTheDocument()
  })
})

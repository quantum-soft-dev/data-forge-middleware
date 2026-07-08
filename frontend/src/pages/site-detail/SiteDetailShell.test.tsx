import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SiteDetailShell } from './SiteDetailShell'
import type { Site } from '@/entities/site'

vi.mock('@/widgets/delta-sync/DeltaSyncWidget', () => ({
  DeltaSyncWidget: () => <div data-testid="delta-sync-widget">delta sync</div>,
}))

vi.mock('@/widgets/upload-history/BatchListWidget', () => ({
  BatchListWidget: ({ siteId }: { siteId?: string }) => (
    <div data-testid="batch-list" data-site-id={siteId}>batch list</div>
  ),
}))

const v2Site: Site = {
  id: 'site-v2',
  accountId: 'account-1',
  siteName: 'store-berlin-01',
  name: 'Store Berlin',
  isActive: true,
  retentionDays: 45,
  createdAt: '2026-05-01T00:00:00Z',
  siteType: 'POSTGRES_CDC',
  clientApiVersion: 'V2',
}

const v1Site: Site = {
  ...v2Site,
  id: 'site-v1',
  siteName: 'warehouse-legacy',
  name: 'Warehouse Legacy',
  siteType: 'DBF',
  clientApiVersion: 'V1',
}

describe('SiteDetailShell (F3)', () => {
  it('renders breadcrumb, title, chips and subline', async () => {
    const onBack = vi.fn()
    render(<SiteDetailShell site={v2Site} canManage={false} onBack={onBack} />)

    expect(screen.getByRole('heading', { name: 'store-berlin-01' })).toBeInTheDocument()
    expect(screen.getByText('POSTGRES_CDC')).toBeInTheDocument()
    expect(screen.getByText('Delta v2')).toBeInTheDocument()
    expect(screen.getByText('Active')).toBeInTheDocument()
    // Prototype subline copy for V2 sites (F14); the cadence is derived from
    // SYNC_STATE_POLL_MS so the copy cannot drift from the actual poll interval (review r3).
    expect(
      screen.getByText('Changelog stream over gRPC. Metrics refresh every 20 seconds.'),
    ).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /all sites/i }))
    expect(onBack).toHaveBeenCalled()
  })

  it('shows Upload history and Delta Sync tabs for a V2 site, Upload history is default', () => {
    render(<SiteDetailShell site={v2Site} canManage={false} onBack={() => {}} />)

    expect(screen.getByRole('tab', { name: 'Upload history' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Delta Sync' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Upload history' })).toHaveAttribute('aria-selected', 'true')

    const batchList = screen.getByTestId('batch-list')
    expect(batchList).toHaveAttribute('data-site-id', 'site-v2')
  })

  it('does not render a Delta Sync tab at all for a V1 site', () => {
    render(<SiteDetailShell site={v1Site} canManage={false} onBack={() => {}} />)

    expect(screen.getByRole('tab', { name: 'Upload history' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Delta Sync' })).not.toBeInTheDocument()
    expect(screen.getByText('v1')).toBeInTheDocument()
    expect(screen.getByText('Full snapshot uploads over HTTP (v1).')).toBeInTheDocument()
  })

  it('styles the active tab per the prototype (blue border pill)', () => {
    render(<SiteDetailShell site={v2Site} canManage={false} onBack={() => {}} />)

    const active = screen.getByRole('tab', { name: 'Upload history' })
    expect(active).toHaveAttribute('aria-selected', 'true')
    expect(active.className).toContain('data-[state=active]:border-brand')
    expect(active.className).toContain('data-[state=active]:text-brand')
  })
})

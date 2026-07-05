import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SiteListItem } from './SiteListItem'
import type { Site } from '@/entities/site/model/types'

const baseSite: Site = {
  id: 'site-1',
  accountId: 'account-1',
  siteName: 'example.com',
  name: 'Example',
  isActive: true,
  retentionDays: 45,
  createdAt: '2025-01-01T00:00:00Z',
  siteType: 'DBF',
  clientApiVersion: 'V1',
}

describe('SiteListItem retention controls', () => {
  const site: Site = baseSite

  it('enables save when retention days change', async () => {
    const onUpdateRetention = vi.fn()

    render(
      <SiteListItem
        site={site}
        showRetentionControls
        onUpdateRetention={onUpdateRetention}
      />
    )

    const saveButton = screen.getByRole('button', { name: /save/i })
    expect(saveButton).toBeDisabled()

    const input = screen.getByRole('spinbutton')
    await userEvent.clear(input)
    await userEvent.type(input, '30')

    expect(saveButton).toBeEnabled()
    await userEvent.click(saveButton)

    expect(onUpdateRetention).toHaveBeenCalledWith('site-1', 30)
  })
})

describe('SiteListItem API version chip (F2)', () => {
  it('renders a blue "Delta v2" chip for V2 sites', () => {
    render(<SiteListItem site={{ ...baseSite, clientApiVersion: 'V2' }} />)

    const chip = screen.getByText('Delta v2')
    expect(chip).toBeInTheDocument()
    expect(chip.className).toContain('bg-[#EBF2FB]')
    expect(chip.className).toContain('text-[#3C82D8]')
  })

  it('renders a grey "v1" chip for V1 sites', () => {
    render(<SiteListItem site={{ ...baseSite, clientApiVersion: 'V1' }} />)

    const chip = screen.getByText('v1')
    expect(chip).toBeInTheDocument()
    expect(chip.className).toContain('bg-[#F5F5F4]')
    expect(chip.className).toContain('text-[#736F6D]')
  })

  it('places the API chip in the badge cluster after the type badge', () => {
    render(<SiteListItem site={{ ...baseSite, clientApiVersion: 'V2', siteType: 'POSTGRES_CDC' }} />)

    const typeBadge = screen.getByText('Postgres CDC')
    const apiChip = screen.getByText('Delta v2')
    expect(typeBadge.compareDocumentPosition(apiChip) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })
})

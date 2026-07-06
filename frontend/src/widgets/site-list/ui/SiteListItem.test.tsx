import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SiteListItem } from './SiteListItem'
import { monitoringTokens, severityTokens } from '@/shared/ui/tokens'
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
    expect(chip).toHaveStyle({
      background: monitoringTokens.blue50,
      color: monitoringTokens.primary,
    })
  })

  it('renders a grey "v1" chip for V1 sites', () => {
    render(<SiteListItem site={{ ...baseSite, clientApiVersion: 'V1' }} />)

    const chip = screen.getByText('v1')
    expect(chip).toBeInTheDocument()
    expect(chip).toHaveStyle({
      background: monitoringTokens.subtleBg,
      color: monitoringTokens.textSecondary,
    })
  })

  it('places the API chip in the badge cluster after the type badge', () => {
    render(<SiteListItem site={{ ...baseSite, clientApiVersion: 'V2', siteType: 'POSTGRES_CDC' }} />)

    const typeBadge = screen.getByText('Postgres CDC')
    const apiChip = screen.getByText('Delta v2')
    expect(typeBadge.compareDocumentPosition(apiChip) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })
})

describe('SiteListItem monitoring treatment (T020)', () => {
  it('renders the Active status as a success pill with dot', () => {
    render(<SiteListItem site={baseSite} />)

    const pill = screen.getByText('Active')
    expect(pill).toHaveStyle({
      background: severityTokens.healthy.bg,
      color: severityTokens.healthy.text,
    })
    expect(pill.querySelector('span')).not.toBeNull() // dot
  })

  it('renders the Inactive status as a neutral pill with dot', () => {
    render(<SiteListItem site={{ ...baseSite, isActive: false }} />)

    expect(screen.getByText('Inactive')).toHaveStyle({
      background: monitoringTokens.subtleBg,
      color: monitoringTokens.textSecondary,
    })
  })

  it('renders the site name in monitoring typography', () => {
    render(<SiteListItem site={baseSite} />)
    const name = screen.getByText('example.com')
    expect(name.className).toContain('text-[15px]')
    expect(name.className).toContain('font-medium')
    expect(name.className).not.toContain('font-semibold')
    expect(name.className).not.toContain('text-lg')
  })

  it('renders Delete as a destructive-outline action', () => {
    render(<SiteListItem site={baseSite} onDelete={vi.fn()} />)
    const del = screen.getByRole('button', { name: /delete/i })
    expect(del.className).toContain('border-danger-border')
    expect(del.className).toContain('text-danger-text')
    expect(del.className).not.toContain('bg-danger-solid')
  })

  it('renders the retention input via the Input primitive (rounded-lg, hairline)', () => {
    render(<SiteListItem site={baseSite} showRetentionControls onUpdateRetention={vi.fn()} />)
    const input = screen.getByRole('spinbutton')
    expect(input.className).toContain('rounded-lg')
    expect(input.className).not.toContain('border-gray-200')
  })
})

import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SiteListItem } from './SiteListItem'
import type { Site } from '@/entities/site/model/types'

describe('SiteListItem retention controls', () => {
  const site: Site = {
    id: 'site-1',
    accountId: 'account-1',
    domain: 'example.com',
    name: 'Example',
    isActive: true,
    retentionDays: 45,
    createdAt: '2025-01-01T00:00:00Z',
  }

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

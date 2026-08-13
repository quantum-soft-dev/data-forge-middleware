/**
 * Tests for PluginSecretDialog
 *
 * The dialog is the single place a plugin secret is ever displayed: the backend
 * keeps only a hash, so a closed dialog means the value is gone for good.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { PluginSecretDialog } from '../PluginSecretDialog'
import type { PluginSecret } from '../../model/pluginSecret'

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

const apiKeySecret: PluginSecret = {
  kind: 'api-key',
  pluginId: 'bit-bi',
  value: 'plk_abcdefghijklmnopqrstuvwxyz012345',
}

const basicAuthSecret: PluginSecret = {
  kind: 'basic-auth',
  pluginId: 'parquet-export',
  login: 'pex_Ab3xY9Qm2Lk4',
  password: 'Kf82abcdefghijklmnopqrstuvwxyz12',
}

describe('PluginSecretDialog', () => {
  const writeText = vi.fn().mockResolvedValue(undefined)

  /**
   * userEvent.setup() installs its own clipboard stub, so the spy has to be
   * planted afterwards to be the one the component actually calls.
   */
  const setupUserWithClipboard = () => {
    const user = userEvent.setup()
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
      writable: true,
    })
    return user
  }

  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('should render nothing when there is no secret', () => {
    const { container } = render(<PluginSecretDialog secret={null} onClose={vi.fn()} />)

    expect(container).toBeEmptyDOMElement()
  })

  it('should show the api key and warn that it is shown once', () => {
    render(<PluginSecretDialog secret={apiKeySecret} pluginName="Bit BI" onClose={vi.fn()} />)

    expect(screen.getByText('plk_abcdefghijklmnopqrstuvwxyz012345')).toBeInTheDocument()
    expect(screen.getByText(/won't be shown again/i)).toBeInTheDocument()
    expect(screen.getByText(/Bit BI/)).toBeInTheDocument()
  })

  it('should copy the api key to the clipboard', async () => {
    const user = setupUserWithClipboard()
    render(<PluginSecretDialog secret={apiKeySecret} onClose={vi.fn()} />)

    await user.click(screen.getByRole('button', { name: /copy api key/i }))

    expect(writeText).toHaveBeenCalledWith('plk_abcdefghijklmnopqrstuvwxyz012345')
  })

  it('should show login and password as separate fields for basic auth credentials', () => {
    render(<PluginSecretDialog secret={basicAuthSecret} pluginName="Parquet Export" onClose={vi.fn()} />)

    expect(screen.getByText('pex_Ab3xY9Qm2Lk4')).toBeInTheDocument()
    expect(screen.getByText('Kf82abcdefghijklmnopqrstuvwxyz12')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /copy api key/i })).not.toBeInTheDocument()
  })

  it('should copy login and password independently', async () => {
    const user = setupUserWithClipboard()
    render(<PluginSecretDialog secret={basicAuthSecret} onClose={vi.fn()} />)

    await user.click(screen.getByRole('button', { name: /copy login/i }))
    expect(writeText).toHaveBeenCalledWith('pex_Ab3xY9Qm2Lk4')

    await user.click(screen.getByRole('button', { name: /copy password/i }))
    expect(writeText).toHaveBeenCalledWith('Kf82abcdefghijklmnopqrstuvwxyz12')
  })

  it('should call onClose when the acknowledge button is clicked', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(<PluginSecretDialog secret={apiKeySecret} onClose={onClose} />)

    await user.click(screen.getByRole('button', { name: /saved/i }))

    expect(onClose).toHaveBeenCalled()
  })
})

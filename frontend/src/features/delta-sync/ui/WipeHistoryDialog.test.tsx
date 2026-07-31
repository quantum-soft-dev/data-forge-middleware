import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { WipeHistoryDialog } from './DeltaSyncDialogs'

/**
 * The wipe is irreversible and its damage is invisible until a client next connects, so the gate
 * that stops a mis-click on the wrong site is the behaviour worth pinning down.
 */
describe('WipeHistoryDialog (#89)', () => {
  const SITE = 'store-01.example.com'

  it('keeps the destructive button disabled until the site name is typed back', async () => {
    const onConfirm = vi.fn()
    render(
      <WipeHistoryDialog open={true} onOpenChange={vi.fn()} siteName={SITE} onConfirm={onConfirm} />,
    )

    const button = screen.getByRole('button', { name: 'Wipe history' })
    expect(button).toBeDisabled()

    await userEvent.type(screen.getByLabelText(/confirm the site name/i), SITE)

    expect(button).toBeEnabled()
    await userEvent.click(button)
    expect(onConfirm).toHaveBeenCalledWith(SITE)
  })

  it('rejects a near-miss', async () => {
    render(
      <WipeHistoryDialog open={true} onOpenChange={vi.fn()} siteName={SITE} onConfirm={vi.fn()} />,
    )

    // The neighbouring site in the list — exactly the mistake the gate exists to catch.
    await userEvent.type(screen.getByLabelText(/confirm the site name/i), 'store-02.example.com')

    expect(screen.getByRole('button', { name: 'Wipe history' })).toBeDisabled()
  })

  it('clears the typed confirmation when reopened', async () => {
    const { rerender } = render(
      <WipeHistoryDialog open={true} onOpenChange={vi.fn()} siteName={SITE} onConfirm={vi.fn()} />,
    )
    await userEvent.type(screen.getByLabelText(/confirm the site name/i), SITE)

    rerender(
      <WipeHistoryDialog open={false} onOpenChange={vi.fn()} siteName={SITE} onConfirm={vi.fn()} />,
    )
    rerender(
      <WipeHistoryDialog open={true} onOpenChange={vi.fn()} siteName={SITE} onConfirm={vi.fn()} />,
    )

    // Otherwise a dialog dismissed and reopened would come back already unlocked.
    expect(screen.getByRole('button', { name: 'Wipe history' })).toBeDisabled()
  })

  it('holds the button while the wipe is in flight', async () => {
    render(
      <WipeHistoryDialog
        open={true}
        onOpenChange={vi.fn()}
        siteName={SITE}
        onConfirm={vi.fn()}
        wiping={true}
      />,
    )
    await userEvent.type(screen.getByLabelText(/confirm the site name/i), SITE)

    expect(screen.getByRole('button', { name: /wiping/i })).toBeDisabled()
  })

  it('says what is destroyed and what survives', () => {
    render(
      <WipeHistoryDialog open={true} onOpenChange={vi.fn()} siteName={SITE} onConfirm={vi.fn()} />,
    )

    expect(screen.getByText(/including its table schema/i)).toBeInTheDocument()
    expect(screen.getByText(/the site itself and its credentials stay/i)).toBeInTheDocument()
    expect(screen.getByText(/bit bi baselines\s+re-capture automatically/i)).toBeInTheDocument()
  })
})

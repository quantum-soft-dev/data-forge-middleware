import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { RebaselineCard } from './RebaselineCard'

describe('RebaselineCard (F9)', () => {
  it('shows the destructive-outline button when no re-baseline is pending', async () => {
    const onRequest = vi.fn()
    render(<RebaselineCard rebaselineRequested={false} onRequest={onRequest} onCancel={vi.fn()} />)

    expect(screen.getByText(/use when the changelog and checkpoints have diverged/i)).toBeInTheDocument()

    const button = screen.getByRole('button', { name: 'Request full re-baseline' })
    await userEvent.click(button)
    expect(onRequest).toHaveBeenCalled()
  })

  it('swaps the button for the amber pending pill while the flag is set', () => {
    render(<RebaselineCard rebaselineRequested={true} onRequest={vi.fn()} onCancel={vi.fn()} />)

    expect(screen.queryByRole('button', { name: 'Request full re-baseline' })).not.toBeInTheDocument()
    expect(screen.getByText('Full snapshot scheduled on next connect')).toBeInTheDocument()
  })

  it('offers the cancel action only while a re-baseline is pending (#84)', async () => {
    const onCancel = vi.fn()
    const { rerender } = render(
      <RebaselineCard rebaselineRequested={false} onRequest={vi.fn()} onCancel={onCancel} />,
    )
    expect(screen.queryByRole('button', { name: /cancel request/i })).not.toBeInTheDocument()

    rerender(<RebaselineCard rebaselineRequested={true} onRequest={vi.fn()} onCancel={onCancel} />)

    await userEvent.click(screen.getByRole('button', { name: /cancel request/i }))
    expect(onCancel).toHaveBeenCalled()
  })

  it('holds the cancel button while the cancellation is in flight', () => {
    render(
      <RebaselineCard rebaselineRequested={true} onRequest={vi.fn()} onCancel={vi.fn()} cancelling={true} />,
    )

    expect(screen.getByRole('button', { name: /cancel request/i })).toBeDisabled()
  })
})

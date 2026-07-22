import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { RebaselineCard } from './RebaselineCard'

describe('RebaselineCard (F9)', () => {
  it('shows the destructive-outline button when no re-baseline is pending', async () => {
    const onRequest = vi.fn()
    render(<RebaselineCard rebaselineRequested={false} onRequest={onRequest} />)

    expect(screen.getByText(/use when the changelog and checkpoints have diverged/i)).toBeInTheDocument()

    const button = screen.getByRole('button', { name: 'Request full re-baseline' })
    await userEvent.click(button)
    expect(onRequest).toHaveBeenCalled()
  })

  it('swaps the button for the amber pending pill while the flag is set', () => {
    render(<RebaselineCard rebaselineRequested={true} onRequest={vi.fn()} />)

    expect(screen.queryByRole('button', { name: 'Request full re-baseline' })).not.toBeInTheDocument()
    expect(screen.getByText('Full snapshot scheduled on next connect')).toBeInTheDocument()
  })
})

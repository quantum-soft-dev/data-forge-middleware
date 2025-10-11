import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AccountSearch } from '@/features/account-search/AccountSearch'

describe('AccountSearch', () => {
  it('should render search input', () => {
    render(<AccountSearch onSearch={vi.fn()} />)

    const input = screen.getByPlaceholderText(/search/i)
    expect(input).toBeInTheDocument()
  })

  it('should call onSearch with debounced value', async () => {
    const onSearch = vi.fn()
    const user = userEvent.setup()

    render(<AccountSearch onSearch={onSearch} />)

    const input = screen.getByPlaceholderText(/search/i)
    await user.type(input, 'john')

    // Should debounce (400ms per spec)
    expect(onSearch).not.toHaveBeenCalled()

    // Wait for debounce
    await waitFor(
      () => {
        expect(onSearch).toHaveBeenCalledWith('john')
      },
      { timeout: 500 }
    )
  })

  it('should display search icon', () => {
    render(<AccountSearch onSearch={vi.fn()} />)

    // Search icon should be visible
    const icon = screen.getByTestId('search-icon')
    expect(icon).toBeInTheDocument()
  })

  it('should clear search when clicking clear button', async () => {
    const onSearch = vi.fn()
    const user = userEvent.setup()

    render(<AccountSearch onSearch={onSearch} />)

    const input = screen.getByPlaceholderText(/search/i)
    await user.type(input, 'test')

    await waitFor(() => {
      expect(onSearch).toHaveBeenCalledWith('test')
    })

    // Click clear button (X icon)
    const clearButton = screen.getByRole('button', { name: /clear/i })
    await user.click(clearButton)

    expect(input).toHaveValue('')
    await waitFor(() => {
      expect(onSearch).toHaveBeenCalledWith('')
    })
  })
})

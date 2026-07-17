import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, act, fireEvent } from '@testing-library/react'
import { AccountSearch } from '@/features/account-search/AccountSearch'

const DEBOUNCE_MS = 400

/**
 * Debounce behaviour is asserted with fake timers so the result never depends on
 * how much wall-clock time elapses under test-suite load. Keystrokes go through
 * `fireEvent` rather than `user-event`, whose async internals deadlock against
 * faked timers.
 */
describe('AccountSearch', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  function setup(onSearch: (query: string) => void) {
    render(<AccountSearch onSearch={onSearch} />)
    return screen.getByPlaceholderText(/search/i)
  }

  /** Types `text` the way a user would — one cumulative change event per character. */
  function type(input: HTMLElement, text: string) {
    for (let i = 1; i <= text.length; i++) {
      fireEvent.change(input, { target: { value: text.slice(0, i) } })
    }
  }

  function elapseDebounce() {
    act(() => {
      vi.advanceTimersByTime(DEBOUNCE_MS)
    })
  }

  it('should render search input', () => {
    setup(vi.fn())

    expect(screen.getByPlaceholderText(/search/i)).toBeInTheDocument()
  })

  it('should display search icon', () => {
    setup(vi.fn())

    expect(screen.getByTestId('search-icon')).toBeInTheDocument()
  })

  it('should not call onSearch on mount', () => {
    const onSearch = vi.fn()
    setup(onSearch)

    elapseDebounce()

    expect(onSearch).not.toHaveBeenCalled()
  })

  it('should call onSearch with debounced value', () => {
    const onSearch = vi.fn()
    const input = setup(onSearch)

    type(input, 'john')
    expect(onSearch).not.toHaveBeenCalled()

    elapseDebounce()

    expect(onSearch).toHaveBeenCalledWith('john')
  })

  it('should only call onSearch once for a burst of keystrokes', () => {
    const onSearch = vi.fn()
    const input = setup(onSearch)

    type(input, 'john')
    elapseDebounce()

    expect(onSearch).toHaveBeenCalledTimes(1)
  })

  it('should clear search when clicking clear button', () => {
    const onSearch = vi.fn()
    const input = setup(onSearch)

    type(input, 'test')
    elapseDebounce()
    expect(onSearch).toHaveBeenCalledWith('test')

    fireEvent.click(screen.getByRole('button', { name: /clear/i }))
    expect(input).toHaveValue('')

    elapseDebounce()

    expect(onSearch).toHaveBeenCalledWith('')
  })
})

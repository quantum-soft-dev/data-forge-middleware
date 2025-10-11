import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import { useAccounts } from '@/entities/account/api/useAccounts'
import * as accountClient from '@/entities/account/api/client'

// Mock the client
vi.mock('@/entities/account/api/client')

const createWrapper = () => {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  })
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children)
}

describe('useAccounts', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('should fetch subscribers successfully', async () => {
    const mockData = {
      content: [
        {
          id: '1',
          name: 'John Doe',
          email: 'john@example.com',
          phone: '+1234567890',
          company: 'Acme',
          status: 'active' as const,
          createdAt: '2024-01-01T00:00:00Z',
        },
      ],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
    }

    vi.spyOn(accountClient, 'fetchAccounts').mockResolvedValue(mockData)

    const { result } = renderHook(() => useAccounts({ page: 1, pageSize: 10 }), {
      wrapper: createWrapper(),
    })

    expect(result.current.isLoading).toBe(true)

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true)
    })

    expect(result.current.data).toEqual(mockData)
    expect(accountClient.fetchAccounts).toHaveBeenCalledWith({
      page: 1,
      size: 10,
      search: undefined,
      status: undefined,
    })
  })

  it('should handle search parameter', async () => {
    const mockFetch = vi.spyOn(accountClient, 'fetchAccounts').mockResolvedValue({
      content: [],
      page: 0,
      size: 10,
      totalElements: 0,
      totalPages: 0,
    })

    renderHook(() => useAccounts({ page: 1, pageSize: 10, search: 'john' }), {
      wrapper: createWrapper(),
    })

    await waitFor(() => {
      expect(mockFetch).toHaveBeenCalled()
    })

    expect(mockFetch).toHaveBeenCalledWith({
      page: 1,
      size: 10,
      search: 'john',
      status: undefined,
    })
  })

  it('should handle status filter', async () => {
    const mockFetch = vi.spyOn(accountClient, 'fetchAccounts').mockResolvedValue({
      content: [],
      page: 0,
      size: 10,
      totalElements: 0,
      totalPages: 0,
    })

    renderHook(() => useAccounts({ page: 1, pageSize: 10, status: 'active' }), {
      wrapper: createWrapper(),
    })

    await waitFor(() => {
      expect(mockFetch).toHaveBeenCalled()
    })

    expect(mockFetch).toHaveBeenCalledWith({
      page: 1,
      size: 10,
      search: undefined,
      status: 'active',
    })
  })

  it('should handle API errors', async () => {
    const mockError = new Error('Network error')
    vi.spyOn(accountClient, 'fetchAccounts').mockRejectedValue(mockError)

    const { result } = renderHook(() => useAccounts({ page: 1, pageSize: 10 }), {
      wrapper: createWrapper(),
    })

    await waitFor(() => {
      expect(result.current.isError).toBe(true)
    })

    expect(result.current.error).toEqual(mockError)
  })
})

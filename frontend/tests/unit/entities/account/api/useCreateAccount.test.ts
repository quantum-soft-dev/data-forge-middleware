import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import { useCreateAccount } from '@/entities/account/api/useCreateAccount'
import * as accountClient from '@/entities/account/api/client'

vi.mock('@/entities/account/api/client')

describe('useCreateAccount', () => {
  let queryClient: QueryClient

  const createWrapper = () => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    })
    return ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children)
  }

  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('should call createAccount API with correct data', async () => {
    const mockAccount = {
      id: '1',
      name: 'John Doe',
      email: 'john@example.com',
      phone: '+1234567890',
      company: 'Acme Corp',
      status: 'active' as const,
      createdAt: '2025-01-15T10:00:00Z',
    }

    vi.mocked(accountClient.createAccount).mockResolvedValue(mockAccount)

    const { result } = renderHook(() => useCreateAccount(), {
      wrapper: createWrapper(),
    })

    const formData = {
      name: 'John Doe',
      email: 'john@example.com',
      phone: '+1234567890',
      company: 'Acme Corp',
    }

    result.current.mutate(formData)

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true)
    })

    expect(accountClient.createAccount).toHaveBeenCalledWith(formData)
    expect(result.current.data).toEqual(mockAccount)
  })

  it('should invalidate account list queries on success', async () => {
    const mockAccount = {
      id: '2',
      name: 'Jane Smith',
      email: 'jane@example.com',
      phone: null,
      company: null,
      status: 'active' as const,
      createdAt: '2025-01-15T10:30:00Z',
    }

    vi.mocked(accountClient.createAccount).mockResolvedValue(mockAccount)

    const { result } = renderHook(() => useCreateAccount(), {
      wrapper: createWrapper(),
    })

    const invalidateQueriesSpy = vi.spyOn(queryClient, 'invalidateQueries')

    result.current.mutate({
      name: 'Jane Smith',
      email: 'jane@example.com',
      phone: null,
      company: null,
    })

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true)
    })

    expect(invalidateQueriesSpy).toHaveBeenCalledWith({
      queryKey: ['accounts', 'list'],
    })
  })

  it('should handle API errors', async () => {
    const error = new Error('Email already exists')
    vi.mocked(accountClient.createAccount).mockRejectedValue(error)

    const { result } = renderHook(() => useCreateAccount(), {
      wrapper: createWrapper(),
    })

    result.current.mutate({
      name: 'Test User',
      email: 'duplicate@example.com',
      phone: null,
      company: null,
    })

    await waitFor(() => {
      expect(result.current.isError).toBe(true)
    })

    expect(result.current.error).toEqual(error)
  })

  it('should track loading state during mutation', async () => {
    const mockAccount = {
      id: '3',
      name: 'Bob Johnson',
      email: 'bob@example.com',
      phone: null,
      company: null,
      status: 'active' as const,
      createdAt: '2025-01-15T11:00:00Z',
    }

    vi.mocked(accountClient.createAccount).mockImplementation(
      () => new Promise(resolve => setTimeout(() => resolve(mockAccount), 100))
    )

    const { result } = renderHook(() => useCreateAccount(), {
      wrapper: createWrapper(),
    })

    expect(result.current.isPending).toBe(false)

    result.current.mutate({
      name: 'Bob Johnson',
      email: 'bob@example.com',
      phone: null,
      company: null,
    })

    await waitFor(() => {
      expect(result.current.isPending).toBe(true)
    })

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true)
    })

    expect(result.current.isPending).toBe(false)
  })
})

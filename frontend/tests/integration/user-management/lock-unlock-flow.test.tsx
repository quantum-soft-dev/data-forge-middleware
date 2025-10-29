/**
 * Integration Test for Lock-Unlock Flow (T049)
 *
 * Tests the complete user flow:
 * - Navigate to account details
 * - Lock account → verify status changes to locked
 * - Unlock account → verify status reverts to unlocked
 *
 * Uses mocked API responses to simulate backend behavior
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { AccountDetailsPage } from '@/pages/accounts/details/AccountDetailsPage'
import type { AccountWithKeycloakStatus } from '@/entities/account/model/types'

// Mock API client
const mockApiClient = {
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
}

vi.mock('@/shared/api/apiClient', () => ({
  apiClient: mockApiClient,
}))

// Helper to create mock account
const createMockAccount = (overrides?: Partial<AccountWithKeycloakStatus>): AccountWithKeycloakStatus => ({
  id: '550e8400-e29b-41d4-a716-446655440000',
  keycloakUserId: 'a3bb189e-8bf9-3888-9912-ace4e6543002',
  email: 'test@example.com',
  name: 'Test User',
  phone: '+1234567890',
  company: 'Test Company',
  isActive: true,
  createdAt: '2025-10-28T12:00:00Z',
  updatedAt: '2025-10-28T12:00:00Z',
  keycloakEnabled: true,
  passwordTemporary: false,
  passwordExpiresAt: null,
  lastLogin: '2025-10-28T14:30:00Z',
  role: 'USER',
  ...overrides,
})

// Helper to render component with all necessary providers
const renderAccountDetailsPage = (accountId: string) => {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/admin/users/${accountId}`]}>
        <Routes>
          <Route path="/admin/users/:id" element={<AccountDetailsPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('Lock-Unlock Flow Integration Test', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('completes full lock-unlock cycle successfully', async () => {
    const accountId = '550e8400-e29b-41d4-a716-446655440000'
    const initialAccount = createMockAccount({
      id: accountId,
      keycloakEnabled: true, // Start unlocked
    })

    // Mock initial account fetch
    mockApiClient.get.mockResolvedValueOnce({
      data: initialAccount,
    })

    // Mock audit logs fetch (empty initially)
    mockApiClient.get.mockResolvedValueOnce({
      data: {
        content: [],
        page: 0,
        size: 20,
        totalElements: 0,
        totalPages: 0,
      },
    })

    renderAccountDetailsPage(accountId)

    // Wait for account to load
    await waitFor(() => {
      expect(screen.getByText('Test User')).toBeInTheDocument()
      expect(screen.getByText('test@example.com')).toBeInTheDocument()
    })

    // Verify account starts in unlocked state
    expect(screen.getByText(/enabled/i)).toBeInTheDocument()

    // Step 1: Lock the account
    // Mock lock API response
    const lockedAccount = createMockAccount({
      id: accountId,
      keycloakEnabled: false, // Locked
      updatedAt: '2025-10-28T15:00:00Z',
    })

    mockApiClient.post.mockResolvedValueOnce({
      data: lockedAccount,
    })

    // Mock subsequent account refetch after lock
    mockApiClient.get.mockResolvedValueOnce({
      data: lockedAccount,
    })

    // Find and click lock button
    const lockButton = screen.getByRole('button', { name: /lock account/i })
    expect(lockButton).not.toBeDisabled()
    fireEvent.click(lockButton)

    // Wait for confirmation dialog
    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
      expect(screen.getByText(/are you sure you want to lock/i)).toBeInTheDocument()
    })

    // Confirm lock action
    const confirmButton = screen.getByRole('button', { name: /^lock account$/i })
    fireEvent.click(confirmButton)

    // Verify lock API was called
    await waitFor(() => {
      expect(mockApiClient.post).toHaveBeenCalledWith(
        `/api/admin/accounts/${accountId}/lock`,
        undefined
      )
    })

    // Wait for UI to update showing locked status
    await waitFor(() => {
      expect(screen.getByText(/disabled/i)).toBeInTheDocument()
    })

    // Verify lock button is now disabled (account already locked)
    await waitFor(() => {
      const lockButtonAfter = screen.getByRole('button', { name: /lock account/i })
      expect(lockButtonAfter).toBeDisabled()
    })

    // Step 2: Unlock the account
    // Mock unlock API response
    const unlockedAccount = createMockAccount({
      id: accountId,
      keycloakEnabled: true, // Unlocked again
      updatedAt: '2025-10-28T15:05:00Z',
    })

    mockApiClient.post.mockResolvedValueOnce({
      data: unlockedAccount,
    })

    // Mock subsequent account refetch after unlock
    mockApiClient.get.mockResolvedValueOnce({
      data: unlockedAccount,
    })

    // Find and click unlock button
    const unlockButton = screen.getByRole('button', { name: /unlock account/i })
    expect(unlockButton).not.toBeDisabled()
    fireEvent.click(unlockButton)

    // Wait for unlock confirmation dialog
    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
      expect(screen.getByText(/are you sure you want to unlock/i)).toBeInTheDocument()
    })

    // Confirm unlock action
    const confirmUnlockButton = screen.getByRole('button', { name: /^unlock account$/i })
    fireEvent.click(confirmUnlockButton)

    // Verify unlock API was called
    await waitFor(() => {
      expect(mockApiClient.post).toHaveBeenCalledWith(
        `/api/admin/accounts/${accountId}/unlock`,
        undefined
      )
    })

    // Wait for UI to update showing unlocked status
    await waitFor(() => {
      expect(screen.getByText(/enabled/i)).toBeInTheDocument()
    })

    // Verify unlock button is now disabled (account already unlocked)
    await waitFor(() => {
      const unlockButtonAfter = screen.getByRole('button', { name: /unlock account/i })
      expect(unlockButtonAfter).toBeDisabled()
    })

    // Verify lock button is enabled again
    await waitFor(() => {
      const lockButtonFinal = screen.getByRole('button', { name: /lock account/i })
      expect(lockButtonFinal).not.toBeDisabled()
    })
  })

  it('handles lock API error gracefully', async () => {
    const accountId = '550e8400-e29b-41d4-a716-446655440000'
    const initialAccount = createMockAccount({
      id: accountId,
      keycloakEnabled: true,
    })

    // Mock initial account fetch
    mockApiClient.get.mockResolvedValueOnce({
      data: initialAccount,
    })

    // Mock audit logs fetch
    mockApiClient.get.mockResolvedValueOnce({
      data: {
        content: [],
        page: 0,
        size: 20,
        totalElements: 0,
        totalPages: 0,
      },
    })

    renderAccountDetailsPage(accountId)

    // Wait for account to load
    await waitFor(() => {
      expect(screen.getByText('Test User')).toBeInTheDocument()
    })

    // Mock lock API error
    mockApiClient.post.mockRejectedValueOnce({
      response: {
        status: 500,
        data: {
          message: 'Failed to lock account',
        },
      },
    })

    // Click lock button
    const lockButton = screen.getByRole('button', { name: /lock account/i })
    fireEvent.click(lockButton)

    // Confirm in dialog
    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })

    const confirmButton = screen.getByRole('button', { name: /^lock account$/i })
    fireEvent.click(confirmButton)

    // Verify lock API was called
    await waitFor(() => {
      expect(mockApiClient.post).toHaveBeenCalled()
    })

    // Account status should remain unlocked (error handling)
    // The dialog should close even on error
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
  })

  it('handles unlock API error gracefully', async () => {
    const accountId = '550e8400-e29b-41d4-a716-446655440000'
    const initialAccount = createMockAccount({
      id: accountId,
      keycloakEnabled: false, // Start locked
    })

    // Mock initial account fetch
    mockApiClient.get.mockResolvedValueOnce({
      data: initialAccount,
    })

    // Mock audit logs fetch
    mockApiClient.get.mockResolvedValueOnce({
      data: {
        content: [],
        page: 0,
        size: 20,
        totalElements: 0,
        totalPages: 0,
      },
    })

    renderAccountDetailsPage(accountId)

    // Wait for account to load
    await waitFor(() => {
      expect(screen.getByText('Test User')).toBeInTheDocument()
    })

    // Mock unlock API error
    mockApiClient.post.mockRejectedValueOnce({
      response: {
        status: 400,
        data: {
          message: 'Account is already unlocked',
        },
      },
    })

    // Click unlock button
    const unlockButton = screen.getByRole('button', { name: /unlock account/i })
    fireEvent.click(unlockButton)

    // Confirm in dialog
    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })

    const confirmButton = screen.getByRole('button', { name: /^unlock account$/i })
    fireEvent.click(confirmButton)

    // Verify unlock API was called
    await waitFor(() => {
      expect(mockApiClient.post).toHaveBeenCalled()
    })

    // Dialog should close even on error
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
  })

  it('prevents double-lock attempts', async () => {
    const accountId = '550e8400-e29b-41d4-a716-446655440000'
    const lockedAccount = createMockAccount({
      id: accountId,
      keycloakEnabled: false, // Already locked
    })

    // Mock initial account fetch
    mockApiClient.get.mockResolvedValueOnce({
      data: lockedAccount,
    })

    // Mock audit logs fetch
    mockApiClient.get.mockResolvedValueOnce({
      data: {
        content: [],
        page: 0,
        size: 20,
        totalElements: 0,
        totalPages: 0,
      },
    })

    renderAccountDetailsPage(accountId)

    // Wait for account to load
    await waitFor(() => {
      expect(screen.getByText('Test User')).toBeInTheDocument()
    })

    // Verify lock button is disabled (already locked)
    const lockButton = screen.getByRole('button', { name: /lock account/i })
    expect(lockButton).toBeDisabled()

    // Attempting to click should do nothing
    fireEvent.click(lockButton)

    // No dialog should appear
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    // No API call should be made
    expect(mockApiClient.post).not.toHaveBeenCalled()
  })

  it('prevents double-unlock attempts', async () => {
    const accountId = '550e8400-e29b-41d4-a716-446655440000'
    const unlockedAccount = createMockAccount({
      id: accountId,
      keycloakEnabled: true, // Already unlocked
    })

    // Mock initial account fetch
    mockApiClient.get.mockResolvedValueOnce({
      data: unlockedAccount,
    })

    // Mock audit logs fetch
    mockApiClient.get.mockResolvedValueOnce({
      data: {
        content: [],
        page: 0,
        size: 20,
        totalElements: 0,
        totalPages: 0,
      },
    })

    renderAccountDetailsPage(accountId)

    // Wait for account to load
    await waitFor(() => {
      expect(screen.getByText('Test User')).toBeInTheDocument()
    })

    // Verify unlock button is disabled (already unlocked)
    const unlockButton = screen.getByRole('button', { name: /unlock account/i })
    expect(unlockButton).toBeDisabled()

    // Attempting to click should do nothing
    fireEvent.click(unlockButton)

    // No dialog should appear
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    // No API call should be made
    expect(mockApiClient.post).not.toHaveBeenCalled()
  })
})

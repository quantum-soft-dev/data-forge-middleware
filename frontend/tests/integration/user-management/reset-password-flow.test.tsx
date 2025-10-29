/**
 * Integration Test for Reset Password Flow (T061)
 *
 * Tests the complete password reset workflow with mocked API:
 * 1. Render ResetPasswordDialog
 * 2. Confirm password reset
 * 3. Verify API call with correct account ID
 * 4. Verify temporary password displays
 * 5. Copy password to clipboard
 * 6. Close dialog
 *
 * Uses real React Query mutations with mocked API client
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ResetPasswordDialog } from '@/features/user-management/ui/ResetPasswordDialog'
import * as apiClientModule from '@/shared/api/apiClient'
import type { AccountWithKeycloakStatus } from '@/entities/account/model/types'

// Mock the API client module
vi.mock('@/shared/api/apiClient', () => ({
  apiClient: {
    post: vi.fn(),
  },
}))

describe('Reset Password Flow - Integration Test', () => {
  let queryClient: QueryClient
  let user: ReturnType<typeof userEvent.setup>

  const mockAccount: AccountWithKeycloakStatus = {
    id: 'acc-reset-123',
    email: 'reset.user@example.com',
    name: 'Reset Test User',
    role: 'USER',
    isActive: true,
    createdAt: '2025-10-29T10:00:00Z',
    keycloakUserId: 'keycloak-reset-123',
    accountEnabled: true,
    lastLogin: null,
  }

  const mockAccountNoKeycloak: AccountWithKeycloakStatus = {
    ...mockAccount,
    id: 'acc-no-keycloak',
    email: 'no.keycloak@example.com',
    keycloakUserId: null,
    accountEnabled: false,
  }

  beforeEach(() => {
    // Create a fresh QueryClient for each test
    queryClient = new QueryClient({
      defaultOptions: {
        queries: {
          retry: false,
        },
        mutations: {
          retry: false,
        },
      },
    })

    // Setup user-event
    user = userEvent.setup()

    // Clear all mocks before each test
    vi.clearAllMocks()

    // Mock clipboard API
    Object.assign(navigator, {
      clipboard: {
        writeText: vi.fn().mockResolvedValue(undefined),
      },
    })
  })

  afterEach(() => {
    queryClient.clear()
  })

  const renderWithProviders = (component: React.ReactElement) => {
    return render(
      <QueryClientProvider client={queryClient}>
        {component}
      </QueryClientProvider>
    )
  }

  it('completes full reset password flow with success', async () => {
    const mockOnSuccess = vi.fn()
    const mockOnClose = vi.fn()

    // Mock API response
    const mockResponse = {
      data: {
        accountId: mockAccount.id,
        temporaryPassword: 'ResetPass789!@#',
        expiresAt: '2025-11-28T10:00:00Z',
      },
    }

    vi.mocked(apiClientModule.apiClient.post).mockResolvedValue(mockResponse)

    renderWithProviders(
      <ResetPasswordDialog
        account={mockAccount}
        onSuccess={mockOnSuccess}
        onClose={mockOnClose}
        isOpen={true}
      />
    )

    // Step 1: Verify confirmation dialog is rendered
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText(/reset password/i)).toBeInTheDocument()
    expect(screen.getByText(mockAccount.email)).toBeInTheDocument()

    // Step 2: Click Reset Password button
    const resetButton = screen.getByRole('button', { name: /reset password/i })
    await user.click(resetButton)

    // Step 3: Verify API call with correct account ID
    await waitFor(() => {
      expect(apiClientModule.apiClient.post).toHaveBeenCalledWith(
        `/api/admin/accounts/${mockAccount.id}/reset-password`,
        {}
      )
    })

    // Step 4: Verify temporary password is displayed
    await waitFor(() => {
      expect(screen.getByText(/password reset successfully/i)).toBeInTheDocument()
    })

    expect(screen.getByText(mockResponse.data.temporaryPassword)).toBeInTheDocument()
    expect(screen.getByText(/save this password now/i)).toBeInTheDocument()

    // Step 5: Copy password to clipboard
    const copyButton = screen.getByRole('button', { name: /copy password to clipboard/i })
    await user.click(copyButton)

    await waitFor(() => {
      expect(navigator.clipboard.writeText).toHaveBeenCalledWith(mockResponse.data.temporaryPassword)
    })

    // Verify onSuccess was called
    expect(mockOnSuccess).toHaveBeenCalledTimes(1)

    // Step 6: Close dialog
    const closeButton = screen.getByRole('button', { name: /close/i })
    await user.click(closeButton)

    expect(mockOnClose).toHaveBeenCalledTimes(1)
  })

  it('shows warning messages throughout the flow', async () => {
    const mockResponse = {
      data: {
        accountId: mockAccount.id,
        temporaryPassword: 'ResetPass789!@#',
        expiresAt: '2025-11-28T10:00:00Z',
      },
    }

    vi.mocked(apiClientModule.apiClient.post).mockResolvedValue(mockResponse)

    renderWithProviders(
      <ResetPasswordDialog
        account={mockAccount}
        isOpen={true}
      />
    )

    // Verify pre-reset warnings
    expect(screen.getByText(/a temporary password will be generated/i)).toBeInTheDocument()
    expect(screen.getByText(/the user's current password will be invalidated immediately/i)).toBeInTheDocument()

    // Click reset
    const resetButton = screen.getByRole('button', { name: /reset password/i })
    await user.click(resetButton)

    // Verify post-reset warnings
    await waitFor(() => {
      expect(screen.getByText(/save this password now\. it will not be shown again/i)).toBeInTheDocument()
    })

    expect(screen.getByText(/the user will be required to change this password on their next login/i)).toBeInTheDocument()
    expect(screen.getByText(/expires:/i)).toBeInTheDocument()
    expect(screen.getByText(/30 days/i)).toBeInTheDocument()
  })

  it('handles API error correctly', async () => {
    const mockOnSuccess = vi.fn()
    const mockOnClose = vi.fn()

    // Mock API error
    const mockError = {
      response: {
        status: 400,
        data: {
          message: 'Account does not have Keycloak integration',
        },
      },
    }

    vi.mocked(apiClientModule.apiClient.post).mockRejectedValue(mockError)

    renderWithProviders(
      <ResetPasswordDialog
        account={mockAccount}
        onSuccess={mockOnSuccess}
        onClose={mockOnClose}
        isOpen={true}
      />
    )

    // Click reset
    const resetButton = screen.getByRole('button', { name: /reset password/i })
    await user.click(resetButton)

    // Verify API was called
    await waitFor(() => {
      expect(apiClientModule.apiClient.post).toHaveBeenCalled()
    })

    // Verify success screen did NOT appear
    expect(screen.queryByText(/password reset successfully/i)).not.toBeInTheDocument()

    // Verify onSuccess was NOT called
    expect(mockOnSuccess).not.toHaveBeenCalled()

    // Dialog should still be open (error state)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('disables reset button for account without Keycloak integration', () => {
    renderWithProviders(
      <ResetPasswordDialog
        account={mockAccountNoKeycloak}
        isOpen={true}
      />
    )

    const resetButton = screen.getByRole('button', { name: /reset password/i })
    expect(resetButton).toBeDisabled()
    expect(resetButton).toHaveAttribute('title', 'Account does not have Keycloak integration')
  })

  it('disables buttons while mutation is pending', async () => {
    // Mock API with delay to simulate pending state
    vi.mocked(apiClientModule.apiClient.post).mockImplementation(() => {
      return new Promise((resolve) => {
        setTimeout(() => {
          resolve({
            data: {
              accountId: mockAccount.id,
              temporaryPassword: 'ResetPass789!@#',
              expiresAt: '2025-11-28T10:00:00Z',
            },
          })
        }, 100)
      })
    })

    renderWithProviders(
      <ResetPasswordDialog
        account={mockAccount}
        isOpen={true}
      />
    )

    // Click reset button
    const resetButton = screen.getByRole('button', { name: /reset password/i })
    await user.click(resetButton)

    // Verify buttons are disabled while pending
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /resetting\.\.\./i })).toBeDisabled()
      expect(screen.getByRole('button', { name: /cancel/i })).toBeDisabled()
    })

    // Wait for completion
    await waitFor(() => {
      expect(screen.getByText(/password reset successfully/i)).toBeInTheDocument()
    })
  })

  it('allows canceling before reset', async () => {
    const mockOnClose = vi.fn()

    renderWithProviders(
      <ResetPasswordDialog
        account={mockAccount}
        onClose={mockOnClose}
        isOpen={true}
      />
    )

    // Click cancel button
    const cancelButton = screen.getByRole('button', { name: /cancel/i })
    await user.click(cancelButton)

    // Verify onClose was called
    expect(mockOnClose).toHaveBeenCalledTimes(1)

    // Verify API was NOT called
    expect(apiClientModule.apiClient.post).not.toHaveBeenCalled()
  })

  it('shows temporary password expiration date', async () => {
    const expirationDate = '2025-11-28T10:00:00Z'
    const mockResponse = {
      data: {
        accountId: mockAccount.id,
        temporaryPassword: 'ResetPass789!@#',
        expiresAt: expirationDate,
      },
    }

    vi.mocked(apiClientModule.apiClient.post).mockResolvedValue(mockResponse)

    renderWithProviders(
      <ResetPasswordDialog
        account={mockAccount}
        isOpen={true}
      />
    )

    // Click reset
    const resetButton = screen.getByRole('button', { name: /reset password/i })
    await user.click(resetButton)

    // Wait for success screen
    await waitFor(() => {
      expect(screen.getByText(/password reset successfully/i)).toBeInTheDocument()
    })

    // Verify expiration date is shown
    expect(screen.getByText(/expires:/i)).toBeInTheDocument()
    const formattedDate = new Date(expirationDate).toLocaleDateString()
    expect(screen.getByText(new RegExp(formattedDate, 'i'))).toBeInTheDocument()
  })

  it('handles copy failure gracefully', async () => {
    const mockResponse = {
      data: {
        accountId: mockAccount.id,
        temporaryPassword: 'ResetPass789!@#',
        expiresAt: '2025-11-28T10:00:00Z',
      },
    }

    vi.mocked(apiClientModule.apiClient.post).mockResolvedValue(mockResponse)

    // Mock clipboard failure
    Object.assign(navigator, {
      clipboard: {
        writeText: vi.fn().mockRejectedValue(new Error('Clipboard access denied')),
      },
    })

    renderWithProviders(
      <ResetPasswordDialog
        account={mockAccount}
        isOpen={true}
      />
    )

    // Click reset
    const resetButton = screen.getByRole('button', { name: /reset password/i })
    await user.click(resetButton)

    // Wait for success screen
    await waitFor(() => {
      expect(screen.getByText(mockResponse.data.temporaryPassword)).toBeInTheDocument()
    })

    // Try to copy
    const copyButton = screen.getByRole('button', { name: /copy password to clipboard/i })
    await user.click(copyButton)

    // Verify writeText was called (but failed)
    await waitFor(() => {
      expect(navigator.clipboard.writeText).toHaveBeenCalled()
    })

    // Dialog should still be open (failure shouldn't close dialog)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('clears state when closing after success', async () => {
    const mockOnClose = vi.fn()
    const mockResponse = {
      data: {
        accountId: mockAccount.id,
        temporaryPassword: 'ResetPass789!@#',
        expiresAt: '2025-11-28T10:00:00Z',
      },
    }

    vi.mocked(apiClientModule.apiClient.post).mockResolvedValue(mockResponse)

    const { rerender } = renderWithProviders(
      <ResetPasswordDialog
        account={mockAccount}
        onClose={mockOnClose}
        isOpen={true}
      />
    )

    // Click reset
    const resetButton = screen.getByRole('button', { name: /reset password/i })
    await user.click(resetButton)

    // Wait for success
    await waitFor(() => {
      expect(screen.getByText(/password reset successfully/i)).toBeInTheDocument()
    })

    // Copy password
    const copyButton = screen.getByRole('button', { name: /copy password to clipboard/i })
    await user.click(copyButton)

    // Close dialog
    const closeButton = screen.getByRole('button', { name: /close/i })
    await user.click(closeButton)

    expect(mockOnClose).toHaveBeenCalledTimes(1)

    // Reopen dialog - should be back to initial state
    rerender(
      <QueryClientProvider client={queryClient}>
        <ResetPasswordDialog
          account={mockAccount}
          onClose={mockOnClose}
          isOpen={true}
        />
      </QueryClientProvider>
    )

    // Should show confirmation dialog again (not success screen)
    expect(screen.getByText(/are you sure you want to reset the password for/i)).toBeInTheDocument()
    expect(screen.queryByText(/password reset successfully/i)).not.toBeInTheDocument()
  })

  it('displays account email correctly throughout the flow', async () => {
    const mockResponse = {
      data: {
        accountId: mockAccount.id,
        temporaryPassword: 'ResetPass789!@#',
        expiresAt: '2025-11-28T10:00:00Z',
      },
    }

    vi.mocked(apiClientModule.apiClient.post).mockResolvedValue(mockResponse)

    renderWithProviders(
      <ResetPasswordDialog
        account={mockAccount}
        isOpen={true}
      />
    )

    // Email shown in confirmation
    expect(screen.getByText(mockAccount.email)).toBeInTheDocument()

    // Click reset
    const resetButton = screen.getByRole('button', { name: /reset password/i })
    await user.click(resetButton)

    // Email shown in success
    await waitFor(() => {
      expect(screen.getByText(/password reset successfully/i)).toBeInTheDocument()
    })

    expect(screen.getByText(mockAccount.email)).toBeInTheDocument()
  })
})

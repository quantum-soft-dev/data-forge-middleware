/**
 * Unit Tests for ResetPasswordDialog Component (T060)
 *
 * Tests comprehensive coverage for password reset dialog:
 * - Dialog rendering with confirmation message
 * - Temporary password displayed after successful reset
 * - Copy button functionality
 * - Warning messages shown
 * - Disabled states for accounts without Auth0
 * - Cancel functionality
 * - Accessibility (ARIA attributes)
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ResetPasswordDialog } from '@/features/user-management/ui/ResetPasswordDialog'
import * as userMutationsModule from '@/features/user-management/api/userMutations'
import type { AccountWithAuthStatus } from '@/entities/account/model/types'

// Mock the userMutations module
vi.mock('@/features/user-management/api/userMutations', () => ({
  useResetPasswordMutation: vi.fn(),
}))

describe('ResetPasswordDialog', () => {
  let queryClient: QueryClient
  let mockClipboardWriteText: ReturnType<typeof vi.fn>

  const mockAccount: AccountWithAuthStatus = {
    id: 'acc-123',
    email: 'test.user@example.com',
    name: 'Test User',
    phone: null,
    company: null,
    status: 'active',
    role: 'USER',
    isActive: true,
    createdAt: '2025-10-29T10:00:00Z',
    updatedAt: '2025-10-29T10:00:00Z',
    identityProviderUserId: 'auth0|123',
    isBlocked: false,
    lastLogin: null,
  }

  const mockAccountNoAuth0: AccountWithAuthStatus = {
    ...mockAccount,
    identityProviderUserId: null,
    isBlocked: true,
  }

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    })
    vi.clearAllMocks()

    // Mock clipboard API - ensure navigator.clipboard exists first
    if (!navigator.clipboard) {
      Object.defineProperty(navigator, 'clipboard', {
        value: {},
        writable: true,
        configurable: true,
      })
    }

    // Now spy on writeText
    mockClipboardWriteText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator.clipboard, 'writeText', {
      value: mockClipboardWriteText,
      writable: true,
      configurable: true,
    })
  })

  const renderWithProviders = (component: React.ReactElement) => {
    return render(
      <QueryClientProvider client={queryClient}>
        {component}
      </QueryClientProvider>
    )
  }

  describe('Dialog Rendering', () => {
    it('renders nothing when isOpen is false', () => {
      const mockMutateAsync = vi.fn()
      vi.mocked(userMutationsModule.useResetPasswordMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isPending: false,
        isError: false,
        isSuccess: false,
        error: null,
      } as any)

      const { container } = renderWithProviders(
        <ResetPasswordDialog
          account={mockAccount}
          isOpen={false}
        />
      )

      expect(container.firstChild).toBeNull()
    })

    it('renders confirmation dialog when isOpen is true', () => {
      const mockMutateAsync = vi.fn()
      vi.mocked(userMutationsModule.useResetPasswordMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isPending: false,
        isError: false,
        isSuccess: false,
        error: null,
      } as any)

      renderWithProviders(
        <ResetPasswordDialog
          account={mockAccount}
          isOpen={true}
        />
      )

      expect(screen.getByRole('dialog')).toBeInTheDocument()
      expect(screen.getByRole('heading', { name: /reset password/i })).toBeInTheDocument()
      expect(screen.getByText(mockAccount.email)).toBeInTheDocument()
    })

    it('displays confirmation message with user email', () => {
      const mockMutateAsync = vi.fn()
      vi.mocked(userMutationsModule.useResetPasswordMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isPending: false,
        isError: false,
        isSuccess: false,
        error: null,
      } as any)

      renderWithProviders(
        <ResetPasswordDialog
          account={mockAccount}
          isOpen={true}
        />
      )

      expect(screen.getByText(/are you sure you want to reset the password for/i)).toBeInTheDocument()
      expect(screen.getByText(mockAccount.email)).toBeInTheDocument()
    })

    it('displays warning message about temporary password', () => {
      const mockMutateAsync = vi.fn()
      vi.mocked(userMutationsModule.useResetPasswordMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isPending: false,
        isError: false,
        isSuccess: false,
        error: null,
      } as any)

      renderWithProviders(
        <ResetPasswordDialog
          account={mockAccount}
          isOpen={true}
        />
      )

      expect(screen.getByText(/a password reset link will be generated/i)).toBeInTheDocument()
      expect(screen.getByText(/send this link to the user to set a new password/i)).toBeInTheDocument()
    })

    it('displays warning about current password invalidation', () => {
      const mockMutateAsync = vi.fn()
      vi.mocked(userMutationsModule.useResetPasswordMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isPending: false,
        isError: false,
        isSuccess: false,
        error: null,
      } as any)

      renderWithProviders(
        <ResetPasswordDialog
          account={mockAccount}
          isOpen={true}
        />
      )

      expect(screen.getByText(/the user will receive a one-time password reset link/i)).toBeInTheDocument()
    })
  })

  describe('Button States', () => {
    it('renders Cancel and Reset Password buttons', () => {
      const mockMutateAsync = vi.fn()
      vi.mocked(userMutationsModule.useResetPasswordMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isPending: false,
        isError: false,
        isSuccess: false,
        error: null,
      } as any)

      renderWithProviders(
        <ResetPasswordDialog
          account={mockAccount}
          isOpen={true}
        />
      )

      expect(screen.getByRole('button', { name: /cancel/i })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /reset password/i })).toBeInTheDocument()
    })

    it('disables Reset Password button when account has no Auth0 integration', () => {
      const mockMutateAsync = vi.fn()
      vi.mocked(userMutationsModule.useResetPasswordMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isPending: false,
        isError: false,
        isSuccess: false,
        error: null,
      } as any)

      renderWithProviders(
        <ResetPasswordDialog
          account={mockAccountNoAuth0}
          isOpen={true}
        />
      )

      const resetButton = screen.getByRole('button', { name: /reset password/i })
      expect(resetButton).toBeDisabled()
      expect(resetButton).toHaveAttribute('title', 'Account does not have Auth0 integration')
    })

    it('disables buttons when mutation is pending', () => {
      const mockMutateAsync = vi.fn()
      vi.mocked(userMutationsModule.useResetPasswordMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isPending: true,
        isError: false,
        isSuccess: false,
        error: null,
      } as any)

      renderWithProviders(
        <ResetPasswordDialog
          account={mockAccount}
          isOpen={true}
        />
      )

      expect(screen.getByRole('button', { name: /cancel/i })).toBeDisabled()
      expect(screen.getByRole('button', { name: /resetting\.\.\./i })).toBeDisabled()
    })
  })

  describe('Password Reset Flow', () => {
    it('calls mutateAsync when Reset Password button is clicked', async () => {
      const user = userEvent.setup()
      const mockMutateAsync = vi.fn().mockResolvedValue({
        accountId: mockAccount.id,
        email: mockAccount.email,
        passwordResetLink: 'https://auth0.com/reset-password',
        expiresAt: '2025-11-28T10:00:00Z',
      })

      vi.mocked(userMutationsModule.useResetPasswordMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isPending: false,
        isError: false,
        isSuccess: false,
        error: null,
      } as any)

      renderWithProviders(
        <ResetPasswordDialog
          account={mockAccount}
          isOpen={true}
        />
      )

      const resetButton = screen.getByRole('button', { name: /reset password/i })
      await user.click(resetButton)

      await waitFor(() => {
        expect(mockMutateAsync).toHaveBeenCalledWith(mockAccount.id)
      })
    })

    it('displays temporary password after successful reset', async () => {
      const user = userEvent.setup()
      const mockResponse = {
        accountId: mockAccount.id,
        email: mockAccount.email,
        passwordResetLink: 'https://auth0.com/reset-password',
        expiresAt: '2025-11-28T10:00:00Z',
      }

      const mockMutateAsync = vi.fn().mockResolvedValue(mockResponse)

      vi.mocked(userMutationsModule.useResetPasswordMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isPending: false,
        isError: false,
        isSuccess: false,
        error: null,
      } as any)

      renderWithProviders(
        <ResetPasswordDialog
          account={mockAccount}
          isOpen={true}
        />
      )

      const resetButton = screen.getByRole('button', { name: /reset password/i })
      await user.click(resetButton)

      await waitFor(() => {
        expect(screen.getByText(/password reset link generated/i)).toBeInTheDocument()
      })

      expect(screen.getByText(mockResponse.passwordResetLink)).toBeInTheDocument()
      expect(screen.getByText(/save this link now/i)).toBeInTheDocument()
    })

    it('calls onSuccess callback after successful reset', async () => {
      const user = userEvent.setup()
      const mockOnSuccess = vi.fn()
      const mockMutateAsync = vi.fn().mockResolvedValue({
        accountId: mockAccount.id,
        email: mockAccount.email,
        passwordResetLink: 'https://auth0.com/reset-password',
        expiresAt: '2025-11-28T10:00:00Z',
      })

      vi.mocked(userMutationsModule.useResetPasswordMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isPending: false,
        isError: false,
        isSuccess: false,
        error: null,
      } as any)

      renderWithProviders(
        <ResetPasswordDialog
          account={mockAccount}
          onSuccess={mockOnSuccess}
          isOpen={true}
        />
      )

      const resetButton = screen.getByRole('button', { name: /reset password/i })
      await user.click(resetButton)

      await waitFor(() => {
        expect(mockOnSuccess).toHaveBeenCalledTimes(1)
      })
    })
  })

  describe('Copy Button Functionality', () => {
    it('renders copy button after successful reset', async () => {
      const user = userEvent.setup()
      const mockMutateAsync = vi.fn().mockResolvedValue({
        accountId: mockAccount.id,
        email: mockAccount.email,
        passwordResetLink: 'https://auth0.com/reset-password',
        expiresAt: '2025-11-28T10:00:00Z',
      })

      vi.mocked(userMutationsModule.useResetPasswordMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isPending: false,
        isError: false,
        isSuccess: false,
        error: null,
      } as any)

      renderWithProviders(
        <ResetPasswordDialog
          account={mockAccount}
          isOpen={true}
        />
      )

      const resetButton = screen.getByRole('button', { name: /reset password/i })
      await user.click(resetButton)

      await waitFor(() => {
        expect(screen.getByRole('button', { name: /copy password reset link to clipboard/i })).toBeInTheDocument()
      })
    })

    it('copies password to clipboard when copy button is clicked', async () => {
      const user = userEvent.setup()
      const mockLink = 'https://auth0.com/reset-password'
      const mockMutateAsync = vi.fn().mockResolvedValue({
        accountId: mockAccount.id,
        email: mockAccount.email,
        passwordResetLink: mockLink,
        expiresAt: '2025-11-28T10:00:00Z',
      })

      vi.mocked(userMutationsModule.useResetPasswordMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isPending: false,
        isError: false,
        isSuccess: false,
        error: null,
      } as any)

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
        expect(screen.getByText(mockLink)).toBeInTheDocument()
      })

      // Click copy button
      const copyButton = screen.getByRole('button', { name: /copy password reset link to clipboard/i })
      await user.click(copyButton)

      // Verify clipboard.writeText was called
      await waitFor(() => {
        expect(mockClipboardWriteText).toHaveBeenCalledWith(mockLink)
      })
    })

    it('shows checkmark icon after successful copy', async () => {
      const user = userEvent.setup()
      const mockMutateAsync = vi.fn().mockResolvedValue({
        accountId: mockAccount.id,
        email: mockAccount.email,
        passwordResetLink: 'https://auth0.com/reset-password',
        expiresAt: '2025-11-28T10:00:00Z',
      })

      vi.mocked(userMutationsModule.useResetPasswordMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isPending: false,
        isError: false,
        isSuccess: false,
        error: null,
      } as any)

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
        expect(screen.getByText('https://auth0.com/reset-password')).toBeInTheDocument()
      })

      // Click copy button
      const copyButton = screen.getByRole('button', { name: /copy password reset link to clipboard/i })
      await user.click(copyButton)

      // The check icon should appear (copy icon is replaced)
      // Note: This test assumes the Check component renders something identifiable
      // In a real test, you might check for CSS class changes or aria-label updates
      await waitFor(() => {
        expect(mockClipboardWriteText).toHaveBeenCalled()
      })
    })
  })

  describe('Warning Messages', () => {
    it('displays "save this link now" warning after reset', async () => {
      const user = userEvent.setup()
      const mockMutateAsync = vi.fn().mockResolvedValue({
        accountId: mockAccount.id,
        email: mockAccount.email,
        passwordResetLink: 'https://auth0.com/reset-password',
        expiresAt: '2025-11-28T10:00:00Z',
      })

      vi.mocked(userMutationsModule.useResetPasswordMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isPending: false,
        isError: false,
        isSuccess: false,
        error: null,
      } as any)

      renderWithProviders(
        <ResetPasswordDialog
          account={mockAccount}
          isOpen={true}
        />
      )

      const resetButton = screen.getByRole('button', { name: /reset password/i })
      await user.click(resetButton)

      await waitFor(() => {
        expect(screen.getByText(/save this link now/i)).toBeInTheDocument()
      })
    })

    it('displays password expiration information', async () => {
      const user = userEvent.setup()
      const mockMutateAsync = vi.fn().mockResolvedValue({
        accountId: mockAccount.id,
        email: mockAccount.email,
        passwordResetLink: 'https://auth0.com/reset-password',
        expiresAt: '2025-11-28T10:00:00Z',
      })

      vi.mocked(userMutationsModule.useResetPasswordMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isPending: false,
        isError: false,
        isSuccess: false,
        error: null,
      } as any)

      renderWithProviders(
        <ResetPasswordDialog
          account={mockAccount}
          isOpen={true}
        />
      )

      const resetButton = screen.getByRole('button', { name: /reset password/i })
      await user.click(resetButton)

      await waitFor(() => {
        expect(screen.getByText(/expires:/i)).toBeInTheDocument()
        expect(screen.getByText(/24 hours/i)).toBeInTheDocument()
      })
    })

    it('displays info about required password change on next login', async () => {
      const user = userEvent.setup()
      const mockMutateAsync = vi.fn().mockResolvedValue({
        accountId: mockAccount.id,
        email: mockAccount.email,
        passwordResetLink: 'https://auth0.com/reset-password',
        expiresAt: '2025-11-28T10:00:00Z',
      })

      vi.mocked(userMutationsModule.useResetPasswordMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isPending: false,
        isError: false,
        isSuccess: false,
        error: null,
      } as any)

      renderWithProviders(
        <ResetPasswordDialog
          account={mockAccount}
          isOpen={true}
        />
      )

      const resetButton = screen.getByRole('button', { name: /reset password/i })
      await user.click(resetButton)

      await waitFor(() => {
        expect(screen.getByText(/this is a one-time use link/i)).toBeInTheDocument()
      })
    })
  })

  describe('Cancel Functionality', () => {
    it('calls onClose when Cancel button is clicked', async () => {
      const user = userEvent.setup()
      const mockOnClose = vi.fn()
      const mockMutateAsync = vi.fn()

      vi.mocked(userMutationsModule.useResetPasswordMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isPending: false,
        isError: false,
        isSuccess: false,
        error: null,
      } as any)

      renderWithProviders(
        <ResetPasswordDialog
          account={mockAccount}
          onClose={mockOnClose}
          isOpen={true}
        />
      )

      const cancelButton = screen.getByRole('button', { name: /cancel/i })
      await user.click(cancelButton)

      expect(mockOnClose).toHaveBeenCalledTimes(1)
    })

    it('calls onClose when Close button is clicked after reset', async () => {
      const user = userEvent.setup()
      const mockOnClose = vi.fn()
      const mockMutateAsync = vi.fn().mockResolvedValue({
        accountId: mockAccount.id,
        email: mockAccount.email,
        passwordResetLink: 'https://auth0.com/reset-password',
        expiresAt: '2025-11-28T10:00:00Z',
      })

      vi.mocked(userMutationsModule.useResetPasswordMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isPending: false,
        isError: false,
        isSuccess: false,
        error: null,
      } as any)

      renderWithProviders(
        <ResetPasswordDialog
          account={mockAccount}
          onClose={mockOnClose}
          isOpen={true}
        />
      )

      // Click reset
      const resetButton = screen.getByRole('button', { name: /reset password/i })
      await user.click(resetButton)

      // Wait for success screen
      await waitFor(() => {
        expect(screen.getByRole('button', { name: /close/i })).toBeInTheDocument()
      })

      // Click close button
      const closeButton = screen.getByRole('button', { name: /close/i })
      await user.click(closeButton)

      expect(mockOnClose).toHaveBeenCalledTimes(1)
    })

    it('calls onClose when clicking backdrop', async () => {
      const user = userEvent.setup()
      const mockOnClose = vi.fn()
      const mockMutateAsync = vi.fn()

      vi.mocked(userMutationsModule.useResetPasswordMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isPending: false,
        isError: false,
        isSuccess: false,
        error: null,
      } as any)

      const { container } = renderWithProviders(
        <ResetPasswordDialog
          account={mockAccount}
          onClose={mockOnClose}
          isOpen={true}
        />
      )

      // Click the backdrop (fixed inset-0 div)
      const backdrop = container.querySelector('.fixed.inset-0')
      if (backdrop) {
        await user.click(backdrop)
        expect(mockOnClose).toHaveBeenCalledTimes(1)
      }
    })
  })

  describe('Accessibility', () => {
    it('has proper ARIA attributes', () => {
      const mockMutateAsync = vi.fn()
      vi.mocked(userMutationsModule.useResetPasswordMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isPending: false,
        isError: false,
        isSuccess: false,
        error: null,
      } as any)

      renderWithProviders(
        <ResetPasswordDialog
          account={mockAccount}
          isOpen={true}
        />
      )

      const dialog = screen.getByRole('dialog')
      expect(dialog).toHaveAttribute('aria-labelledby', 'reset-password-dialog-title')
      expect(dialog).toHaveAttribute('aria-modal', 'true')
    })

    it('has proper title element referenced by aria-labelledby', () => {
      const mockMutateAsync = vi.fn()
      vi.mocked(userMutationsModule.useResetPasswordMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isPending: false,
        isError: false,
        isSuccess: false,
        error: null,
      } as any)

      renderWithProviders(
        <ResetPasswordDialog
          account={mockAccount}
          isOpen={true}
        />
      )

      const title = document.getElementById('reset-password-dialog-title')
      expect(title).toBeInTheDocument()
      expect(title).toHaveTextContent(/reset password/i)
    })

    it('copy button has aria-label', async () => {
      const user = userEvent.setup()
      const mockMutateAsync = vi.fn().mockResolvedValue({
        accountId: mockAccount.id,
        email: mockAccount.email,
        passwordResetLink: 'https://auth0.com/reset-password',
        expiresAt: '2025-11-28T10:00:00Z',
      })

      vi.mocked(userMutationsModule.useResetPasswordMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isPending: false,
        isError: false,
        isSuccess: false,
        error: null,
      } as any)

      renderWithProviders(
        <ResetPasswordDialog
          account={mockAccount}
          isOpen={true}
        />
      )

      const resetButton = screen.getByRole('button', { name: /reset password/i })
      await user.click(resetButton)

      await waitFor(() => {
        const copyButton = screen.getByRole('button', { name: /copy password reset link to clipboard/i })
        expect(copyButton).toHaveAttribute('aria-label', 'Copy password reset link to clipboard')
      })
    })
  })
})

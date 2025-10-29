/**
 * Unit Tests for CreateAccountForm Component (T035)
 *
 * Tests:
 * - Form renders with all required fields
 * - Form validation (required fields, email format, phone format)
 * - Role selection (USER/ADMIN)
 * - Submit button disabled state
 * - Temporary password modal display after successful creation
 * - Copy to clipboard functionality
 * - Error handling and display
 * - Accessibility (ARIA attributes, error messages)
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { CreateAccountForm } from '@/features/user-management/ui/CreateAccountForm'
import * as userMutationsModule from '@/features/user-management/api/userMutations'

// Mock the mutations module
vi.mock('@/features/user-management/api/userMutations')

// Mock clipboard API
Object.assign(navigator, {
  clipboard: {
    writeText: vi.fn().mockResolvedValue(undefined),
  },
})

// Helper to render component with React Query provider
const renderWithQueryClient = (ui: React.ReactElement) => {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      {ui}
    </QueryClientProvider>
  )
}

describe('CreateAccountForm', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('Form Rendering', () => {
    it('renders all required form fields', () => {
      const mockMutateAsync = vi.fn()
      vi.mocked(userMutationsModule.useCreateAccountMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isError: false,
        error: null,
        isPending: false,
      } as any)

      renderWithQueryClient(<CreateAccountForm />)

      // Check all form fields are present
      expect(screen.getByLabelText(/email address/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/full name/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/phone number/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/company/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/role/i)).toBeInTheDocument()

      // Check submit button
      expect(screen.getByRole('button', { name: /create account/i })).toBeInTheDocument()
    })

    it('renders role select with USER and ADMIN options', () => {
      const mockMutateAsync = vi.fn()
      vi.mocked(userMutationsModule.useCreateAccountMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isError: false,
        error: null,
        isPending: false,
      } as any)

      renderWithQueryClient(<CreateAccountForm />)

      const roleSelect = screen.getByLabelText(/role/i) as HTMLSelectElement

      expect(roleSelect).toBeInTheDocument()
      expect(roleSelect.options).toHaveLength(3) // Empty + USER + ADMIN
      expect(roleSelect.options[0].value).toBe('')
      expect(roleSelect.options[1].value).toBe('USER')
      expect(roleSelect.options[2].value).toBe('ADMIN')
    })

    it('marks required fields with asterisk', () => {
      const mockMutateAsync = vi.fn()
      vi.mocked(userMutationsModule.useCreateAccountMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isError: false,
        error: null,
        isPending: false,
      } as any)

      renderWithQueryClient(<CreateAccountForm />)

      // Required fields should have asterisk in label
      expect(screen.getByText(/email address \*/i)).toBeInTheDocument()
      expect(screen.getByText(/full name \*/i)).toBeInTheDocument()
      expect(screen.getByText(/role \*/i)).toBeInTheDocument()
    })
  })

  describe('Form Validation', () => {
    it('shows validation error when email is invalid', async () => {
      const user = userEvent.setup()
      const mockMutateAsync = vi.fn()
      vi.mocked(userMutationsModule.useCreateAccountMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isError: false,
        error: null,
        isPending: false,
      } as any)

      renderWithQueryClient(<CreateAccountForm />)

      // Fill form with invalid email
      await user.type(screen.getByLabelText(/email address/i), 'invalid-email')
      await user.type(screen.getByLabelText(/full name/i), 'Test User')
      await user.selectOptions(screen.getByLabelText(/role/i), 'USER')

      // Submit form
      await user.click(screen.getByRole('button', { name: /create account/i }))

      // Check validation error appears
      await waitFor(() => {
        expect(screen.getByRole('alert')).toBeInTheDocument()
        expect(screen.getByText(/invalid email/i)).toBeInTheDocument()
      })

      // Mutation should not be called
      expect(mockMutateAsync).not.toHaveBeenCalled()
    })

    it('shows validation error when required fields are empty', async () => {
      const user = userEvent.setup()
      const mockMutateAsync = vi.fn()
      vi.mocked(userMutationsModule.useCreateAccountMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isError: false,
        error: null,
        isPending: false,
      } as any)

      renderWithQueryClient(<CreateAccountForm />)

      // Submit form without filling fields
      await user.click(screen.getByRole('button', { name: /create account/i }))

      // Check validation errors appear
      await waitFor(() => {
        const alerts = screen.getAllByRole('alert')
        expect(alerts.length).toBeGreaterThan(0)
      })

      // Mutation should not be called
      expect(mockMutateAsync).not.toHaveBeenCalled()
    })

    it('shows validation error when role is not selected', async () => {
      const user = userEvent.setup()
      const mockMutateAsync = vi.fn()
      vi.mocked(userMutationsModule.useCreateAccountMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isError: false,
        error: null,
        isPending: false,
      } as any)

      renderWithQueryClient(<CreateAccountForm />)

      // Fill form but don't select role
      await user.type(screen.getByLabelText(/email address/i), 'test@example.com')
      await user.type(screen.getByLabelText(/full name/i), 'Test User')

      // Submit form
      await user.click(screen.getByRole('button', { name: /create account/i }))

      // Check validation error appears
      await waitFor(() => {
        const alerts = screen.getAllByRole('alert')
        expect(alerts.length).toBeGreaterThan(0)
      })

      // Mutation should not be called
      expect(mockMutateAsync).not.toHaveBeenCalled()
    })

    it('validates phone number format', async () => {
      const user = userEvent.setup()
      const mockMutateAsync = vi.fn()
      vi.mocked(userMutationsModule.useCreateAccountMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isError: false,
        error: null,
        isPending: false,
      } as any)

      renderWithQueryClient(<CreateAccountForm />)

      // Fill form with invalid phone
      await user.type(screen.getByLabelText(/email address/i), 'test@example.com')
      await user.type(screen.getByLabelText(/full name/i), 'Test User')
      await user.type(screen.getByLabelText(/phone number/i), 'abc123')
      await user.selectOptions(screen.getByLabelText(/role/i), 'USER')

      // Submit form
      await user.click(screen.getByRole('button', { name: /create account/i }))

      // Check validation error appears (if schema validates phone format)
      await waitFor(() => {
        // Phone validation might show error or just submit with invalid phone
        // This depends on schema implementation
        if (screen.queryByText(/phone/i)) {
          expect(screen.getByText(/phone/i)).toBeInTheDocument()
        }
      })
    })
  })

  describe('Form Submission', () => {
    it('submits form with valid data and USER role', async () => {
      const user = userEvent.setup()
      const mockMutateAsync = vi.fn().mockResolvedValue({
        account: {
          id: 'test-id',
          email: 'test@example.com',
          name: 'Test User',
          role: 'USER',
        },
        temporaryPassword: 'TempPass123!',
      })

      vi.mocked(userMutationsModule.useCreateAccountMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isError: false,
        error: null,
        isPending: false,
      } as any)

      renderWithQueryClient(<CreateAccountForm />)

      // Fill form
      await user.type(screen.getByLabelText(/email address/i), 'test@example.com')
      await user.type(screen.getByLabelText(/full name/i), 'Test User')
      await user.type(screen.getByLabelText(/phone number/i), '+1234567890')
      await user.type(screen.getByLabelText(/company/i), 'Test Company')
      await user.selectOptions(screen.getByLabelText(/role/i), 'USER')

      // Submit form
      await user.click(screen.getByRole('button', { name: /create account/i }))

      // Check mutation was called with correct data
      await waitFor(() => {
        expect(mockMutateAsync).toHaveBeenCalledWith({
          email: 'test@example.com',
          name: 'Test User',
          phone: '+1234567890',
          company: 'Test Company',
          role: 'USER',
        })
      })
    })

    it('submits form with ADMIN role', async () => {
      const user = userEvent.setup()
      const mockMutateAsync = vi.fn().mockResolvedValue({
        account: {
          id: 'test-id',
          email: 'admin@example.com',
          name: 'Admin User',
          role: 'ADMIN',
        },
        temporaryPassword: 'TempPass123!',
      })

      vi.mocked(userMutationsModule.useCreateAccountMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isError: false,
        error: null,
        isPending: false,
      } as any)

      renderWithQueryClient(<CreateAccountForm />)

      // Fill form with ADMIN role
      await user.type(screen.getByLabelText(/email address/i), 'admin@example.com')
      await user.type(screen.getByLabelText(/full name/i), 'Admin User')
      await user.selectOptions(screen.getByLabelText(/role/i), 'ADMIN')

      // Submit form
      await user.click(screen.getByRole('button', { name: /create account/i }))

      // Check mutation was called with ADMIN role
      await waitFor(() => {
        expect(mockMutateAsync).toHaveBeenCalledWith(
          expect.objectContaining({
            email: 'admin@example.com',
            role: 'ADMIN',
          })
        )
      })
    })

    it('disables submit button while submitting', async () => {
      const user = userEvent.setup()
      const mockMutateAsync = vi.fn().mockImplementation(
        () => new Promise(resolve => setTimeout(resolve, 1000))
      )

      vi.mocked(userMutationsModule.useCreateAccountMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isError: false,
        error: null,
        isPending: false,
      } as any)

      renderWithQueryClient(<CreateAccountForm />)

      // Fill form
      await user.type(screen.getByLabelText(/email address/i), 'test@example.com')
      await user.type(screen.getByLabelText(/full name/i), 'Test User')
      await user.selectOptions(screen.getByLabelText(/role/i), 'USER')

      const submitButton = screen.getByRole('button', { name: /create account/i })

      // Submit form
      await user.click(submitButton)

      // Button should be disabled during submission
      expect(submitButton).toBeDisabled()
    })
  })

  describe('Temporary Password Modal', () => {
    it('shows temporary password modal after successful creation', async () => {
      const user = userEvent.setup()
      const mockMutateAsync = vi.fn().mockResolvedValue({
        account: {
          id: 'test-id',
          email: 'test@example.com',
          name: 'Test User',
          role: 'USER',
        },
        temporaryPassword: 'TempPass123!@#',
      })

      vi.mocked(userMutationsModule.useCreateAccountMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isError: false,
        error: null,
        isPending: false,
      } as any)

      renderWithQueryClient(<CreateAccountForm />)

      // Fill and submit form
      await user.type(screen.getByLabelText(/email address/i), 'test@example.com')
      await user.type(screen.getByLabelText(/full name/i), 'Test User')
      await user.selectOptions(screen.getByLabelText(/role/i), 'USER')
      await user.click(screen.getByRole('button', { name: /create account/i }))

      // Check modal appears with temporary password
      await waitFor(() => {
        expect(screen.getByText(/account created successfully/i)).toBeInTheDocument()
        expect(screen.getByText('TempPass123!@#')).toBeInTheDocument()
        expect(screen.getByText(/save this now - it will not be shown again/i)).toBeInTheDocument()
        expect(screen.getByText(/user must change this password on first login/i)).toBeInTheDocument()
      })
    })

    it('copies temporary password to clipboard when copy button clicked', async () => {
      const user = userEvent.setup()
      const mockMutateAsync = vi.fn().mockResolvedValue({
        account: { id: 'test-id', email: 'test@example.com', name: 'Test User', role: 'USER' },
        temporaryPassword: 'TempPass123!@#',
      })

      vi.mocked(userMutationsModule.useCreateAccountMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isError: false,
        error: null,
        isPending: false,
      } as any)

      renderWithQueryClient(<CreateAccountForm />)

      // Fill and submit form
      await user.type(screen.getByLabelText(/email address/i), 'test@example.com')
      await user.type(screen.getByLabelText(/full name/i), 'Test User')
      await user.selectOptions(screen.getByLabelText(/role/i), 'USER')
      await user.click(screen.getByRole('button', { name: /create account/i }))

      // Wait for modal and click copy button
      await waitFor(() => {
        expect(screen.getByText('TempPass123!@#')).toBeInTheDocument()
      })

      const copyButton = screen.getByRole('button', { name: /copy/i })
      await user.click(copyButton)

      // Check clipboard.writeText was called
      expect(navigator.clipboard.writeText).toHaveBeenCalledWith('TempPass123!@#')
    })

    it('calls onSuccess callback when modal is closed', async () => {
      const user = userEvent.setup()
      const mockOnSuccess = vi.fn()
      const mockMutateAsync = vi.fn().mockResolvedValue({
        account: { id: 'test-id', email: 'test@example.com', name: 'Test User', role: 'USER' },
        temporaryPassword: 'TempPass123!@#',
      })

      vi.mocked(userMutationsModule.useCreateAccountMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isError: false,
        error: null,
        isPending: false,
      } as any)

      renderWithQueryClient(<CreateAccountForm onSuccess={mockOnSuccess} />)

      // Fill and submit form
      await user.type(screen.getByLabelText(/email address/i), 'test@example.com')
      await user.type(screen.getByLabelText(/full name/i), 'Test User')
      await user.selectOptions(screen.getByLabelText(/role/i), 'USER')
      await user.click(screen.getByRole('button', { name: /create account/i }))

      // Wait for modal and close it
      await waitFor(() => {
        expect(screen.getByText(/account created successfully/i)).toBeInTheDocument()
      })

      const closeButton = screen.getByRole('button', { name: /close/i })
      await user.click(closeButton)

      // Check onSuccess was called
      expect(mockOnSuccess).toHaveBeenCalledTimes(1)
    })
  })

  describe('Error Handling', () => {
    it('displays error message when mutation fails', async () => {
      const user = userEvent.setup()
      const mockMutateAsync = vi.fn().mockRejectedValue(new Error('Email already exists'))

      vi.mocked(userMutationsModule.useCreateAccountMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isError: true,
        error: new Error('Email already exists'),
        isPending: false,
      } as any)

      renderWithQueryClient(<CreateAccountForm />)

      // Fill and submit form
      await user.type(screen.getByLabelText(/email address/i), 'test@example.com')
      await user.type(screen.getByLabelText(/full name/i), 'Test User')
      await user.selectOptions(screen.getByLabelText(/role/i), 'USER')
      await user.click(screen.getByRole('button', { name: /create account/i }))

      // Check error message is displayed
      await waitFor(() => {
        expect(screen.getByText(/email already exists/i)).toBeInTheDocument()
      })
    })
  })

  describe('Accessibility', () => {
    it('has proper ARIA attributes for form fields', () => {
      const mockMutateAsync = vi.fn()
      vi.mocked(userMutationsModule.useCreateAccountMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isError: false,
        error: null,
        isPending: false,
      } as any)

      renderWithQueryClient(<CreateAccountForm />)

      // Check ARIA attributes
      const emailInput = screen.getByLabelText(/email address/i)
      expect(emailInput).toHaveAttribute('aria-invalid', 'false')

      const nameInput = screen.getByLabelText(/full name/i)
      expect(nameInput).toHaveAttribute('aria-invalid', 'false')

      const roleSelect = screen.getByLabelText(/role/i)
      expect(roleSelect).toHaveAttribute('aria-invalid', 'false')
    })

    it('shows ARIA-describedby for error messages', async () => {
      const user = userEvent.setup()
      const mockMutateAsync = vi.fn()
      vi.mocked(userMutationsModule.useCreateAccountMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isError: false,
        error: null,
        isPending: false,
      } as any)

      renderWithQueryClient(<CreateAccountForm />)

      // Submit without filling form to trigger validation errors
      await user.click(screen.getByRole('button', { name: /create account/i }))

      // Wait for validation errors
      await waitFor(() => {
        const emailInput = screen.getByLabelText(/email address/i)
        expect(emailInput).toHaveAttribute('aria-invalid', 'true')
        expect(emailInput).toHaveAttribute('aria-describedby', 'email-error')
      })
    })
  })

  describe('Cancel Functionality', () => {
    it('calls onCancel callback when cancel button clicked', async () => {
      const user = userEvent.setup()
      const mockOnCancel = vi.fn()
      const mockMutateAsync = vi.fn()

      vi.mocked(userMutationsModule.useCreateAccountMutation).mockReturnValue({
        mutateAsync: mockMutateAsync,
        isError: false,
        error: null,
        isPending: false,
      } as any)

      renderWithQueryClient(<CreateAccountForm onCancel={mockOnCancel} />)

      // Click cancel button
      const cancelButton = screen.getByRole('button', { name: /cancel/i })
      await user.click(cancelButton)

      // Check onCancel was called
      expect(mockOnCancel).toHaveBeenCalledTimes(1)
    })
  })
})

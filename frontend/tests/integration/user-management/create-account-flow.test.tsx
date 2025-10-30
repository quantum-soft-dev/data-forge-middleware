/**
 * Integration Test for Create Account Flow (T036)
 *
 * Tests the complete create account workflow with mocked API:
 * 1. Render CreateAccountForm
 * 2. Fill in form fields (email, name, role)
 * 3. Submit form
 * 4. Verify API call with correct data
 * 5. Verify temporary password modal displays
 * 6. Close modal
 * 7. Verify form resets
 *
 * Uses real React Query mutations with mocked API client
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { CreateAccountForm } from '@/features/user-management/ui/CreateAccountForm'
import { apiClient } from '@/shared/api/client'

// Mock axios methods directly on the apiClient instance
vi.spyOn(apiClient, 'post')
vi.spyOn(apiClient, 'get')
vi.spyOn(apiClient, 'put')
vi.spyOn(apiClient, 'delete')

// Mock sonner toast
vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}))

describe('Create Account Flow - Integration Test', () => {
  let queryClient: QueryClient
  let user: ReturnType<typeof userEvent.setup>

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

  it('completes full create account flow with USER role', async () => {
    const mockOnSuccess = vi.fn()
    const mockOnCancel = vi.fn()

    // Mock API response for USER role
    const mockResponse = {
      data: {
        account: {
          id: 'acc-12345',
          email: 'john.doe@example.com',
          name: 'John Doe',
          role: 'USER',
          isActive: true,
          createdAt: '2025-10-29T10:00:00Z',
        },
        temporaryPassword: 'TempPass123!@#USER',
      },
    }

    vi.mocked(apiClient.post).mockResolvedValue(mockResponse)

    renderWithProviders(
      <CreateAccountForm onSuccess={mockOnSuccess} onCancel={mockOnCancel} />
    )

    // Step 1: Verify form is rendered
    expect(screen.getByLabelText(/email address/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/full name/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/role/i)).toBeInTheDocument()

    // Step 2: Fill in form fields
    await user.type(screen.getByLabelText(/email address/i), 'john.doe@example.com')
    await user.type(screen.getByLabelText(/full name/i), 'John Doe')
    await user.selectOptions(screen.getByLabelText(/role/i), 'USER')

    // Verify field values
    expect(screen.getByLabelText(/email address/i)).toHaveValue('john.doe@example.com')
    expect(screen.getByLabelText(/full name/i)).toHaveValue('John Doe')
    expect(screen.getByLabelText(/role/i)).toHaveValue('USER')

    // Step 3: Submit form
    const submitButton = screen.getByRole('button', { name: /create account/i })
    await user.click(submitButton)

    // Step 4: Verify API call with correct data
    await waitFor(() => {
      expect(apiClient.post).toHaveBeenCalledWith(
        '/admin/accounts/with-keycloak',
        {
          email: 'john.doe@example.com',
          name: 'John Doe',
          role: 'USER',
        }
      )
    })

    // Step 5: Verify temporary password modal displays
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /account created successfully/i })).toBeInTheDocument()
    })

    expect(screen.getByText(/temporary password/i)).toBeInTheDocument()
    expect(screen.getByText('TempPass123!@#USER')).toBeInTheDocument()
    expect(screen.getByText(/john\.doe@example\.com/i)).toBeInTheDocument()

    // Verify copy button is present
    const copyButton = screen.getByRole('button', { name: /copy password/i })
    expect(copyButton).toBeInTheDocument()

    // Step 6: Close modal
    const closeButton = screen.getByRole('button', { name: /close/i })
    await user.click(closeButton)

    // Step 7: Verify modal is closed
    await waitFor(() => {
      expect(screen.queryByRole('heading', { name: /account created successfully/i })).not.toBeInTheDocument()
    })

    // Verify onSuccess callback was called
    await waitFor(() => {
      expect(mockOnSuccess).toHaveBeenCalledTimes(1)
    })

    // Step 8: Verify form has reset (fields are empty)
    expect(screen.getByLabelText(/email address/i)).toHaveValue('')
    expect(screen.getByLabelText(/full name/i)).toHaveValue('')
    expect(screen.getByLabelText(/role/i)).toHaveValue('')
  })

  it('completes full create account flow with ADMIN role', async () => {
    const mockOnSuccess = vi.fn()
    const mockOnCancel = vi.fn()

    // Mock API response for ADMIN role
    const mockResponse = {
      data: {
        account: {
          id: 'acc-67890',
          email: 'admin@example.com',
          name: 'Admin User',
          role: 'ADMIN',
          isActive: true,
          createdAt: '2025-10-29T10:00:00Z',
        },
        temporaryPassword: 'AdminTemp456!@#',
      },
    }

    vi.mocked(apiClient.post).mockResolvedValue(mockResponse)

    renderWithProviders(
      <CreateAccountForm onSuccess={mockOnSuccess} onCancel={mockOnCancel} />
    )

    // Fill in form with ADMIN role
    await user.type(screen.getByLabelText(/email address/i), 'admin@example.com')
    await user.type(screen.getByLabelText(/full name/i), 'Admin User')
    await user.selectOptions(screen.getByLabelText(/role/i), 'ADMIN')

    // Submit form
    const submitButton = screen.getByRole('button', { name: /create account/i })
    await user.click(submitButton)

    // Verify API call with ADMIN role
    await waitFor(() => {
      expect(apiClient.post).toHaveBeenCalledWith(
        '/admin/accounts/with-keycloak',
        {
          email: 'admin@example.com',
          name: 'Admin User',
          role: 'ADMIN',
        }
      )
    })

    // Verify temporary password modal with ADMIN password
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /account created successfully/i })).toBeInTheDocument()
    })

    expect(screen.getByText('AdminTemp456!@#')).toBeInTheDocument()
    expect(screen.getByText(/admin@example\.com/i)).toBeInTheDocument()

    // Close modal
    const closeButton = screen.getByRole('button', { name: /close/i })
    await user.click(closeButton)

    // Verify success callback and form reset
    await waitFor(() => {
      expect(mockOnSuccess).toHaveBeenCalledTimes(1)
      expect(screen.getByLabelText(/email address/i)).toHaveValue('')
    })
  })

  it('handles API error correctly', async () => {
    const mockOnSuccess = vi.fn()
    const mockOnCancel = vi.fn()

    // Mock API error (e.g., duplicate email)
    const mockError = {
      response: {
        status: 409,
        data: {
          message: 'Account with this email already exists',
        },
      },
    }

    vi.mocked(apiClient.post).mockRejectedValue(mockError)

    renderWithProviders(
      <CreateAccountForm onSuccess={mockOnSuccess} onCancel={mockOnCancel} />
    )

    // Fill in form
    await user.type(screen.getByLabelText(/email address/i), 'existing@example.com')
    await user.type(screen.getByLabelText(/full name/i), 'Existing User')
    await user.selectOptions(screen.getByLabelText(/role/i), 'USER')

    // Submit form
    const submitButton = screen.getByRole('button', { name: /create account/i })
    await user.click(submitButton)

    // Verify API was called
    await waitFor(() => {
      expect(apiClient.post).toHaveBeenCalled()
    })

    // Verify error message is displayed
    await waitFor(() => {
      const errorMessage = screen.getByText(/account with this email already exists/i)
      expect(errorMessage).toBeInTheDocument()
    })

    // Verify modal did NOT open
    expect(screen.queryByRole('heading', { name: /account created successfully/i })).not.toBeInTheDocument()

    // Verify onSuccess was NOT called
    expect(mockOnSuccess).not.toHaveBeenCalled()

    // Verify form is still filled (not reset on error)
    expect(screen.getByLabelText(/email address/i)).toHaveValue('existing@example.com')
    expect(screen.getByLabelText(/full name/i)).toHaveValue('Existing User')
  })

  it('handles validation errors before submission', async () => {
    const mockOnSuccess = vi.fn()
    const mockOnCancel = vi.fn()

    renderWithProviders(
      <CreateAccountForm onSuccess={mockOnSuccess} onCancel={mockOnCancel} />
    )

    // Try to submit without filling fields
    const submitButton = screen.getByRole('button', { name: /create account/i })
    await user.click(submitButton)

    // Verify API was NOT called
    await waitFor(() => {
      expect(apiClient.post).not.toHaveBeenCalled()
    })

    // Verify validation errors are displayed
    await waitFor(() => {
      expect(screen.getByText(/invalid email format/i)).toBeInTheDocument()
      expect(screen.getByText(/name is required/i)).toBeInTheDocument()
      expect(screen.getByText(/role is required/i)).toBeInTheDocument()
    })

    // Verify onSuccess was NOT called
    expect(mockOnSuccess).not.toHaveBeenCalled()
  })

  it('handles invalid email format', async () => {
    const mockOnSuccess = vi.fn()
    const mockOnCancel = vi.fn()

    renderWithProviders(
      <CreateAccountForm onSuccess={mockOnSuccess} onCancel={mockOnCancel} />
    )

    // Fill form with invalid email
    await user.type(screen.getByLabelText(/email address/i), 'invalid-email')
    await user.type(screen.getByLabelText(/full name/i), 'Test User')
    await user.selectOptions(screen.getByLabelText(/role/i), 'USER')

    // Submit form
    const submitButton = screen.getByRole('button', { name: /create account/i })
    await user.click(submitButton)

    // Verify API was NOT called due to validation error
    await waitFor(() => {
      expect(apiClient.post).not.toHaveBeenCalled()
    })

    // Verify email validation error is displayed
    await waitFor(() => {
      expect(screen.getByText(/invalid email format/i)).toBeInTheDocument()
    })

    expect(mockOnSuccess).not.toHaveBeenCalled()
  })

  it('calls onCancel when cancel button is clicked', async () => {
    const mockOnSuccess = vi.fn()
    const mockOnCancel = vi.fn()

    renderWithProviders(
      <CreateAccountForm onSuccess={mockOnSuccess} onCancel={mockOnCancel} />
    )

    // Fill in some fields
    await user.type(screen.getByLabelText(/email address/i), 'test@example.com')
    await user.type(screen.getByLabelText(/full name/i), 'Test User')

    // Click cancel button
    const cancelButton = screen.getByRole('button', { name: /cancel/i })
    await user.click(cancelButton)

    // Verify onCancel was called
    expect(mockOnCancel).toHaveBeenCalledTimes(1)

    // Verify API was NOT called
    expect(apiClient.post).not.toHaveBeenCalled()

    // Verify onSuccess was NOT called
    expect(mockOnSuccess).not.toHaveBeenCalled()
  })

  it('disables submit button while mutation is pending', async () => {
    const mockOnSuccess = vi.fn()
    const mockOnCancel = vi.fn()

    // Mock API with delay to simulate pending state
    vi.mocked(apiClient.post).mockImplementation(() => {
      return new Promise((resolve) => {
        setTimeout(() => {
          resolve({
            data: {
              account: {
                id: 'acc-12345',
                email: 'test@example.com',
                name: 'Test User',
                role: 'USER',
                isActive: true,
                createdAt: '2025-10-29T10:00:00Z',
              },
              temporaryPassword: 'TempPass123!',
            },
          })
        }, 100)
      })
    })

    renderWithProviders(
      <CreateAccountForm onSuccess={mockOnSuccess} onCancel={mockOnCancel} />
    )

    // Fill in form
    await user.type(screen.getByLabelText(/email address/i), 'test@example.com')
    await user.type(screen.getByLabelText(/full name/i), 'Test User')
    await user.selectOptions(screen.getByLabelText(/role/i), 'USER')

    // Submit form
    const submitButton = screen.getByRole('button', { name: /create account/i })
    await user.click(submitButton)

    // Verify button is disabled while pending
    await waitFor(() => {
      expect(submitButton).toBeDisabled()
    })

    // Wait for completion
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /account created successfully/i })).toBeInTheDocument()
    })

    // After success, button should be enabled again (after modal close)
    const closeButton = screen.getByRole('button', { name: /close/i })
    await user.click(closeButton)

    await waitFor(() => {
      expect(submitButton).toBeEnabled()
    })
  })

  it('includes optional fields in API request when provided', async () => {
    const mockOnSuccess = vi.fn()
    const mockOnCancel = vi.fn()

    const mockResponse = {
      data: {
        account: {
          id: 'acc-12345',
          email: 'test@example.com',
          name: 'Test User',
          phone: '+1234567890',
          company: 'Test Company',
          role: 'USER',
          isActive: true,
          createdAt: '2025-10-29T10:00:00Z',
        },
        temporaryPassword: 'TempPass123!',
      },
    }

    vi.mocked(apiClient.post).mockResolvedValue(mockResponse)

    renderWithProviders(
      <CreateAccountForm onSuccess={mockOnSuccess} onCancel={mockOnCancel} />
    )

    // Fill in all fields including optional ones
    await user.type(screen.getByLabelText(/email address/i), 'test@example.com')
    await user.type(screen.getByLabelText(/full name/i), 'Test User')

    const phoneInput = screen.getByLabelText(/phone number/i)
    if (phoneInput) {
      await user.type(phoneInput, '+1234567890')
    }

    const companyInput = screen.getByLabelText(/company/i)
    if (companyInput) {
      await user.type(companyInput, 'Test Company')
    }

    await user.selectOptions(screen.getByLabelText(/role/i), 'USER')

    // Submit form
    const submitButton = screen.getByRole('button', { name: /create account/i })
    await user.click(submitButton)

    // Verify API call includes optional fields
    await waitFor(() => {
      expect(apiClient.post).toHaveBeenCalledWith(
        '/admin/accounts/with-keycloak',
        expect.objectContaining({
          email: 'test@example.com',
          name: 'Test User',
          role: 'USER',
          // Optional fields should be included if provided
          phone: '+1234567890',
          company: 'Test Company',
        })
      )
    })

    // Verify success modal
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /account created successfully/i })).toBeInTheDocument()
    })
  })
})

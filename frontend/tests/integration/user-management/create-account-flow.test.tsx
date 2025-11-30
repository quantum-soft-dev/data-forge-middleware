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
import MockAdapter from 'axios-mock-adapter'
import { CreateAccountForm } from '@/features/user-management/ui/CreateAccountForm'
import { apiClient } from '@/shared/api/client'

// Create axios mock adapter instance
const mockAxios = new MockAdapter(apiClient)

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

    // Reset axios mock adapter
    mockAxios.reset()

    // Clear all mocks before each test
    vi.clearAllMocks()
  })

  afterEach(() => {
    queryClient.clear()
    mockAxios.reset()
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

    // Mock API response for USER role (flat structure matching backend)
    const mockResponse = {
      id: 'acc-12345',
      email: 'john.doe@example.com',
      name: 'John Doe',
      phone: null,
      company: null,
      isActive: true,
      identityProviderUserId: 'auth0|12345',
      temporaryPassword: 'TempPass123!',
      passwordResetUrl: null,
      createdAt: '2025-10-29T10:00:00Z',
    }

    mockAxios.onPost('/v1/accounts').reply(200, mockResponse)

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
      expect(mockAxios.history.post.length).toBe(1)
      expect(mockAxios.history.post[0].url).toBe('/v1/accounts')
      const requestData = JSON.parse(mockAxios.history.post[0].data)
      expect(requestData).toMatchObject({
        email: 'john.doe@example.com',
        name: 'John Doe',
        role: 'USER',
      })
    })

    // Step 5: Verify temporary password modal displays
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /account created successfully/i })).toBeInTheDocument()
    })

    expect(screen.getByText(/temporary password/i)).toBeInTheDocument()
    expect(screen.getByText('TempPass123!')).toBeInTheDocument()

    // Verify copy button is present
    const copyButton = screen.getByRole('button', { name: /copy/i })
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

    // Mock API response for ADMIN role (flat structure matching backend)
    const mockResponse = {
      id: 'acc-67890',
      email: 'admin@example.com',
      name: 'Admin User',
      phone: null,
      company: null,
      isActive: true,
      identityProviderUserId: 'auth0|67890',
      temporaryPassword: 'AdminPass456!',
      passwordResetUrl: null,
      createdAt: '2025-10-29T10:00:00Z',
    }

    mockAxios.onPost('/v1/accounts').reply(200, mockResponse)

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
      expect(mockAxios.history.post.length).toBe(1)
      const requestData = JSON.parse(mockAxios.history.post[0].data)
      expect(requestData).toMatchObject({
        email: 'admin@example.com',
        name: 'Admin User',
        role: 'ADMIN',
      })
    })

    // Verify temporary password modal with ADMIN password
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /account created successfully/i })).toBeInTheDocument()
    })

    expect(screen.getByText('AdminPass456!')).toBeInTheDocument()

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
    mockAxios.onPost('/v1/accounts/with-keycloak').reply(409, {
      message: 'Account with this email already exists',
    })

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
      expect(mockAxios.history.post.length).toBe(1)
    })

    // Wait a bit to ensure no modal appears
    await new Promise(resolve => setTimeout(resolve, 500))

    // Verify modal did NOT open (error was handled by toast, not inline)
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
      expect(mockAxios.history.post.length).toBe(0)
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

    // Fill form with invalid email (no @ symbol)
    await user.type(screen.getByLabelText(/email address/i), 'notanemail')
    await user.type(screen.getByLabelText(/full name/i), 'Test User')
    await user.selectOptions(screen.getByLabelText(/role/i), 'USER')

    // Submit form
    const submitButton = screen.getByRole('button', { name: /create account/i })
    await user.click(submitButton)

    // Wait a bit for potential validation
    await new Promise(resolve => setTimeout(resolve, 500))

    // Verify API was NOT called due to validation error
    // Note: Client-side validation should prevent the API call
    expect(mockAxios.history.post.length).toBe(0)
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
    expect(mockAxios.history.post.length).toBe(0)

    // Verify onSuccess was NOT called
    expect(mockOnSuccess).not.toHaveBeenCalled()
  })

  it('disables submit button while mutation is pending', async () => {
    const mockOnSuccess = vi.fn()
    const mockOnCancel = vi.fn()

    // Mock API with delay to simulate pending state (flat structure matching backend)
    const mockResponse = {
      id: 'acc-12345',
      email: 'test@example.com',
      name: 'Test User',
      phone: null,
      company: null,
      isActive: true,
      identityProviderUserId: 'auth0|12345',
      temporaryPassword: 'TempPass789!',
      passwordResetUrl: null,
      createdAt: '2025-10-29T10:00:00Z',
    }

    mockAxios.onPost('/v1/accounts').reply(() => {
      return new Promise((resolve) => {
        setTimeout(() => {
          resolve([200, mockResponse])
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

    // Query for submit button again after modal closes (form re-renders)
    await waitFor(() => {
      const newSubmitButton = screen.getByRole('button', { name: /create account/i })
      expect(newSubmitButton).toBeEnabled()
    })
  })

  it('includes optional fields in API request when provided', async () => {
    const mockOnSuccess = vi.fn()
    const mockOnCancel = vi.fn()

    // Flat structure matching backend
    const mockResponse = {
      id: 'acc-12345',
      email: 'test@example.com',
      name: 'Test User',
      phone: '+1234567890',
      company: 'Test Company',
      isActive: true,
      identityProviderUserId: 'auth0|12345',
      temporaryPassword: 'TempOptional!',
      passwordResetUrl: null,
      createdAt: '2025-10-29T10:00:00Z',
    }

    mockAxios.onPost('/v1/accounts').reply(200, mockResponse)

    renderWithProviders(
      <CreateAccountForm onSuccess={mockOnSuccess} onCancel={mockOnCancel} />
    )

    // Fill in all fields including optional ones
    await user.type(screen.getByLabelText(/email address/i), 'test@example.com')
    await user.type(screen.getByLabelText(/full name/i), 'Test User')

    const phoneInput = screen.queryByLabelText(/phone number/i)
    if (phoneInput) {
      await user.type(phoneInput, '+1234567890')
    }

    // Note: Company field may not be in the form currently
    const companyInput = screen.queryByLabelText(/company/i)
    if (companyInput) {
      await user.type(companyInput, 'Test Company')
    }

    await user.selectOptions(screen.getByLabelText(/role/i), 'USER')

    // Submit form
    const submitButton = screen.getByRole('button', { name: /create account/i })
    await user.click(submitButton)

    // Verify API call includes optional fields
    await waitFor(() => {
      expect(mockAxios.history.post.length).toBe(1)
      expect(mockAxios.history.post[0].url).toBe('/v1/accounts')
      const requestData = JSON.parse(mockAxios.history.post[0].data)
      expect(requestData).toMatchObject({
        email: 'test@example.com',
        name: 'Test User',
        role: 'USER',
      })
      // Optional fields should be included if form has them
      if (phoneInput) {
        expect(requestData.phone).toBe('+1234567890')
      }
      if (companyInput) {
        expect(requestData.company).toBe('Test Company')
      }
    })

    // Verify success modal
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /account created successfully/i })).toBeInTheDocument()
    })
  })
})

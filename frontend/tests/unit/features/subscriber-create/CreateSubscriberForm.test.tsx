import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { CreateSubscriberForm } from '@/features/subscriber-create/CreateSubscriberForm'

describe('CreateSubscriberForm', () => {
  it('should render all form fields', () => {
    const onSubmit = vi.fn()
    const onCancel = vi.fn()

    render(<CreateSubscriberForm onSubmit={onSubmit} onCancel={onCancel} />)

    expect(screen.getByLabelText(/name/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/email/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/phone/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/company/i)).toBeInTheDocument()
  })

  it('should validate required fields', async () => {
    const onSubmit = vi.fn()
    const onCancel = vi.fn()
    const user = userEvent.setup()

    render(<CreateSubscriberForm onSubmit={onSubmit} onCancel={onCancel} />)

    const submitButton = screen.getByRole('button', { name: /create/i })
    await user.click(submitButton)

    await waitFor(() => {
      expect(screen.getByText(/name is required/i)).toBeInTheDocument()
      expect(screen.getByText(/email is required/i)).toBeInTheDocument()
    })

    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('should validate email format', async () => {
    const onSubmit = vi.fn()
    const onCancel = vi.fn()
    const user = userEvent.setup()

    render(<CreateSubscriberForm onSubmit={onSubmit} onCancel={onCancel} />)

    const nameInput = screen.getByLabelText(/name/i)
    const emailInput = screen.getByLabelText(/email/i)

    await user.type(nameInput, 'John Doe')
    await user.type(emailInput, 'notanemail')

    const submitButton = screen.getByRole('button', { name: /create/i })
    await user.click(submitButton)

    // Wait for validation to complete
    await waitFor(() => {
      // onSubmit should not have been called because validation failed
      expect(onSubmit).not.toHaveBeenCalled()
    })
  })

  it('should transform empty optional fields to null', async () => {
    const onSubmit = vi.fn()
    const onCancel = vi.fn()
    const user = userEvent.setup()

    render(<CreateSubscriberForm onSubmit={onSubmit} onCancel={onCancel} />)

    const nameInput = screen.getByLabelText(/name/i)
    const emailInput = screen.getByLabelText(/email/i)

    await user.type(nameInput, 'John Doe')
    await user.type(emailInput, 'john@example.com')

    const submitButton = screen.getByRole('button', { name: /create/i })
    await user.click(submitButton)

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledWith({
        name: 'John Doe',
        email: 'john@example.com',
        phone: null,
        company: null,
      })
    })
  })

  it('should submit form with valid data', async () => {
    const onSubmit = vi.fn()
    const onCancel = vi.fn()
    const user = userEvent.setup()

    render(<CreateSubscriberForm onSubmit={onSubmit} onCancel={onCancel} />)

    const nameInput = screen.getByLabelText(/name/i)
    const emailInput = screen.getByLabelText(/email/i)
    const phoneInput = screen.getByLabelText(/phone/i)
    const companyInput = screen.getByLabelText(/company/i)

    await user.type(nameInput, 'John Doe')
    await user.type(emailInput, 'john@example.com')
    await user.type(phoneInput, '+1234567890')
    await user.type(companyInput, 'Acme Corp')

    const submitButton = screen.getByRole('button', { name: /create/i })
    await user.click(submitButton)

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledWith({
        name: 'John Doe',
        email: 'john@example.com',
        phone: '+1234567890',
        company: 'Acme Corp',
      })
    })
  })

  it('should call onCancel when cancel button is clicked', async () => {
    const onSubmit = vi.fn()
    const onCancel = vi.fn()
    const user = userEvent.setup()

    render(<CreateSubscriberForm onSubmit={onSubmit} onCancel={onCancel} />)

    const cancelButton = screen.getByRole('button', { name: /cancel/i })
    await user.click(cancelButton)

    expect(onCancel).toHaveBeenCalled()
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('should disable submit button when submitting', async () => {
    const onSubmit = vi.fn(() => new Promise(resolve => setTimeout(resolve, 100)))
    const onCancel = vi.fn()

    render(<CreateSubscriberForm onSubmit={onSubmit} onCancel={onCancel} isSubmitting={true} />)

    const submitButton = screen.getByRole('button', { name: /creating/i })
    expect(submitButton).toBeDisabled()

    const cancelButton = screen.getByRole('button', { name: /cancel/i })
    expect(cancelButton).toBeDisabled()
  })
})

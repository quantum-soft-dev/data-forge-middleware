/**
 * CreateAccountForm Component
 *
 * Form for creating a new account with validation.
 * Uses React Hook Form with Zod resolver for client-side validation.
 */

import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { createAccountSchema, type CreateAccountFormData } from '@/entities/account/model/schema'

interface CreateAccountFormProps {
  onSubmit: (data: CreateAccountFormData) => void
  onCancel: () => void
  isSubmitting?: boolean
}

/**
 * Form component for creating new accounts
 *
 * Features:
 * - Client-side validation with Zod
 * - Required fields: name, email
 * - Optional fields: phone, company (transformed to null if empty)
 * - Email format validation
 * - Auto-focus on first field
 * - Disabled state during submission
 */
export function CreateAccountForm({
  onSubmit,
  onCancel,
  isSubmitting = false,
}: CreateAccountFormProps) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<CreateAccountFormData>({
    resolver: zodResolver(createAccountSchema),
    defaultValues: {
      name: '',
      email: '',
      phone: '',
      company: '',
    },
    mode: 'onSubmit',
  })

  const handleFormSubmit = (data: CreateAccountFormData) => {
    // Transform empty strings to null for optional fields (API expects null, not empty strings)
    const transformedData = {
      name: data.name,
      email: data.email,
      phone: data.phone === '' || data.phone === undefined ? null : data.phone,
      company: data.company === '' || data.company === undefined ? null : data.company,
    }
    onSubmit(transformedData)
  }

  return (
    <form onSubmit={handleSubmit(handleFormSubmit)} className="space-y-4">
      {/* Name Field */}
      <div>
        <label htmlFor="name" className="block text-sm font-medium text-ink-secondary mb-1">
          Name <span className="text-danger-text">*</span>
        </label>
        <input
          id="name"
          type="text"
          autoFocus
          {...register('name')}
          className="h-9 w-full rounded-lg border border-input bg-background px-3 text-sm text-ink focus:outline-none focus:ring-2 focus:ring-ring"
          placeholder="John Doe"
        />
        {errors.name && (
          <p className="mt-1 text-xs text-danger-text">{errors.name.message}</p>
        )}
      </div>

      {/* Email Field */}
      <div>
        <label htmlFor="email" className="block text-sm font-medium text-ink-secondary mb-1">
          Email <span className="text-danger-text">*</span>
        </label>
        <input
          id="email"
          type="email"
          {...register('email')}
          className="h-9 w-full rounded-lg border border-input bg-background px-3 text-sm text-ink focus:outline-none focus:ring-2 focus:ring-ring"
          placeholder="john@example.com"
        />
        {errors.email && (
          <p className="mt-1 text-xs text-danger-text">{errors.email.message}</p>
        )}
      </div>

      {/* Phone Field */}
      <div>
        <label htmlFor="phone" className="block text-sm font-medium text-ink-secondary mb-1">
          Phone
        </label>
        <input
          id="phone"
          type="tel"
          {...register('phone')}
          className="h-9 w-full rounded-lg border border-input bg-background px-3 text-sm text-ink focus:outline-none focus:ring-2 focus:ring-ring"
          placeholder="+1234567890"
        />
        {errors.phone && (
          <p className="mt-1 text-xs text-danger-text">{errors.phone.message}</p>
        )}
      </div>

      {/* Company Field */}
      <div>
        <label htmlFor="company" className="block text-sm font-medium text-ink-secondary mb-1">
          Company
        </label>
        <input
          id="company"
          type="text"
          {...register('company')}
          className="h-9 w-full rounded-lg border border-input bg-background px-3 text-sm text-ink focus:outline-none focus:ring-2 focus:ring-ring"
          placeholder="Acme Corp"
        />
        {errors.company && (
          <p className="mt-1 text-xs text-danger-text">{errors.company.message}</p>
        )}
      </div>

      {/* Buttons */}
      <div className="flex justify-end gap-3 pt-4">
        <button
          type="button"
          onClick={onCancel}
          disabled={isSubmitting}
          className="rounded-lg border border-hairline bg-white px-4 py-2 text-sm font-medium text-ink hover:bg-secondary focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          Cancel
        </button>
        <button
          type="submit"
          disabled={isSubmitting}
          className="rounded-lg bg-brand px-4 py-2 text-sm font-medium text-white hover:bg-brand-hover focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {isSubmitting ? 'Creating...' : 'Create Account'}
        </button>
      </div>
    </form>
  )
}

/**
 * EditAccountForm Component
 *
 * Form for editing an existing account with validation.
 * Uses React Hook Form with Zod resolver for client-side validation.
 * Pre-fills form with current account data.
 */

import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { updateAccountSchema, type UpdateAccountFormData } from '@/entities/account/model/schema'
import type { Account } from '@/entities/account/model/types'

interface EditAccountFormProps {
  account: Account
  onSubmit: (data: UpdateAccountFormData) => void
  onCancel: () => void
  isSubmitting?: boolean
}

/**
 * Form component for editing existing accounts
 *
 * Features:
 * - Pre-filled with current account data
 * - Client-side validation with Zod
 * - All fields optional (partial update)
 * - Email format validation when provided
 * - Auto-focus on first field
 * - Disabled state during submission
 */
export function EditAccountForm({
  account,
  onSubmit,
  onCancel,
  isSubmitting = false,
}: EditAccountFormProps) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<UpdateAccountFormData>({
    resolver: zodResolver(updateAccountSchema),
    defaultValues: {
      id: account.id,
      name: account.name,
      email: account.email,
      phone: account.phone || '',
      company: account.company || '',
    },
    mode: 'onSubmit',
  })

  const handleFormSubmit = (data: UpdateAccountFormData) => {
    // Transform empty strings to null for optional fields (API expects null, not empty strings)
    const transformedData: UpdateAccountFormData = {
      id: data.id,
      name: data.name,
      email: data.email,
      phone: (data.phone === '' || data.phone === undefined ? null : data.phone) as any,
      company: (data.company === '' || data.company === undefined ? null : data.company) as any,
    }
    onSubmit(transformedData)
  }

  return (
    <form onSubmit={handleSubmit(handleFormSubmit)} className="space-y-4">
      {/* Hidden ID field */}
      <input type="hidden" {...register('id')} />

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
          {isSubmitting ? 'Saving...' : 'Save Changes'}
        </button>
      </div>
    </form>
  )
}

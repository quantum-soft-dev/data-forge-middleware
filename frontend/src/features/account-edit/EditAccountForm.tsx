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
        <label htmlFor="name" className="block text-sm font-medium text-gray-700 mb-1">
          Name <span className="text-red-500">*</span>
        </label>
        <input
          id="name"
          type="text"
          autoFocus
          {...register('name')}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          placeholder="John Doe"
        />
        {errors.name && (
          <p className="mt-1 text-xs text-red-600">{errors.name.message}</p>
        )}
      </div>

      {/* Email Field */}
      <div>
        <label htmlFor="email" className="block text-sm font-medium text-gray-700 mb-1">
          Email <span className="text-red-500">*</span>
        </label>
        <input
          id="email"
          type="email"
          {...register('email')}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          placeholder="john@example.com"
        />
        {errors.email && (
          <p className="mt-1 text-xs text-red-600">{errors.email.message}</p>
        )}
      </div>

      {/* Phone Field */}
      <div>
        <label htmlFor="phone" className="block text-sm font-medium text-gray-700 mb-1">
          Phone
        </label>
        <input
          id="phone"
          type="tel"
          {...register('phone')}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          placeholder="+1234567890"
        />
        {errors.phone && (
          <p className="mt-1 text-xs text-red-600">{errors.phone.message}</p>
        )}
      </div>

      {/* Company Field */}
      <div>
        <label htmlFor="company" className="block text-sm font-medium text-gray-700 mb-1">
          Company
        </label>
        <input
          id="company"
          type="text"
          {...register('company')}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          placeholder="Acme Corp"
        />
        {errors.company && (
          <p className="mt-1 text-xs text-red-600">{errors.company.message}</p>
        )}
      </div>

      {/* Buttons */}
      <div className="flex justify-end gap-3 pt-4">
        <button
          type="button"
          onClick={onCancel}
          disabled={isSubmitting}
          className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          Cancel
        </button>
        <button
          type="submit"
          disabled={isSubmitting}
          className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {isSubmitting ? 'Saving...' : 'Save Changes'}
        </button>
      </div>
    </form>
  )
}

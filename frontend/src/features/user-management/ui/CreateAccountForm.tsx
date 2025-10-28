/**
 * CreateAccountForm component for admin user management.
 *
 * Creates new account with Keycloak integration and displays
 * temporary password exactly once.
 *
 * @module features/user-management/ui/CreateAccountForm
 */

import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { createAccountSchema, type CreateAccountFormData } from '../model/userSchemas'
import { useCreateAccountMutation } from '../api/userMutations'

interface CreateAccountFormProps {
  onSuccess?: () => void
  onCancel?: () => void
}

export function CreateAccountForm({ onSuccess, onCancel }: CreateAccountFormProps) {
  const [temporaryPassword, setTemporaryPassword] = useState<string | null>(null)
  const [showPassword, setShowPassword] = useState(false)

  const mutation = useCreateAccountMutation()

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
    reset,
  } = useForm<CreateAccountFormData>({
    resolver: zodResolver(createAccountSchema),
  })

  const onSubmit = async (data: CreateAccountFormData) => {
    try {
      const result = await mutation.mutateAsync(data)
      setTemporaryPassword(result.temporaryPassword)
      setShowPassword(true)
      reset()
    } catch (error) {
      console.error('Failed to create account:', error)
      // Error is handled by mutation onError
    }
  }

  const handleClose = () => {
    setTemporaryPassword(null)
    setShowPassword(false)
    onSuccess?.()
  }

  const copyToClipboard = async () => {
    if (temporaryPassword) {
      await navigator.clipboard.writeText(temporaryPassword)
      // TODO: Show toast notification
    }
  }

  // Show temporary password modal after successful creation
  if (showPassword && temporaryPassword) {
    return (
      <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
        <div className="bg-white rounded-lg p-6 max-w-md w-full">
          <h2 className="text-xl font-bold mb-4">Account Created Successfully</h2>
          <div className="mb-4">
            <p className="text-sm text-gray-600 mb-2">
              Temporary password (save this now - it will not be shown again):
            </p>
            <div className="bg-gray-100 p-3 rounded font-mono text-lg flex items-center justify-between">
              <span>{temporaryPassword}</span>
              <button
                type="button"
                onClick={copyToClipboard}
                className="ml-2 px-3 py-1 bg-blue-500 text-white rounded text-sm hover:bg-blue-600"
              >
                Copy
              </button>
            </div>
          </div>
          <div className="bg-yellow-50 border border-yellow-200 rounded p-3 mb-4">
            <p className="text-sm text-yellow-800">
              ⚠️ User must change this password on first login. Password expires in 30 days.
            </p>
          </div>
          <button
            type="button"
            onClick={handleClose}
            className="w-full px-4 py-2 bg-blue-500 text-white rounded hover:bg-blue-600"
          >
            Close
          </button>
        </div>
      </div>
    )
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
      <div>
        <label htmlFor="email" className="block text-sm font-medium text-gray-700 mb-1">
          Email Address *
        </label>
        <input
          {...register('email')}
          type="email"
          id="email"
          className="w-full px-3 py-2 border border-gray-300 rounded focus:outline-none focus:ring-2 focus:ring-blue-500"
          disabled={isSubmitting}
          aria-invalid={errors.email ? 'true' : 'false'}
          aria-describedby={errors.email ? 'email-error' : undefined}
        />
        {errors.email && (
          <p id="email-error" className="mt-1 text-sm text-red-600" role="alert">
            {errors.email.message}
          </p>
        )}
      </div>

      <div>
        <label htmlFor="name" className="block text-sm font-medium text-gray-700 mb-1">
          Full Name *
        </label>
        <input
          {...register('name')}
          type="text"
          id="name"
          className="w-full px-3 py-2 border border-gray-300 rounded focus:outline-none focus:ring-2 focus:ring-blue-500"
          disabled={isSubmitting}
          aria-invalid={errors.name ? 'true' : 'false'}
          aria-describedby={errors.name ? 'name-error' : undefined}
        />
        {errors.name && (
          <p id="name-error" className="mt-1 text-sm text-red-600" role="alert">
            {errors.name.message}
          </p>
        )}
      </div>

      <div>
        <label htmlFor="phone" className="block text-sm font-medium text-gray-700 mb-1">
          Phone Number
        </label>
        <input
          {...register('phone')}
          type="tel"
          id="phone"
          placeholder="+1234567890"
          className="w-full px-3 py-2 border border-gray-300 rounded focus:outline-none focus:ring-2 focus:ring-blue-500"
          disabled={isSubmitting}
          aria-invalid={errors.phone ? 'true' : 'false'}
          aria-describedby={errors.phone ? 'phone-error' : undefined}
        />
        {errors.phone && (
          <p id="phone-error" className="mt-1 text-sm text-red-600" role="alert">
            {errors.phone.message}
          </p>
        )}
      </div>

      <div>
        <label htmlFor="company" className="block text-sm font-medium text-gray-700 mb-1">
          Company
        </label>
        <input
          {...register('company')}
          type="text"
          id="company"
          className="w-full px-3 py-2 border border-gray-300 rounded focus:outline-none focus:ring-2 focus:ring-blue-500"
          disabled={isSubmitting}
          aria-invalid={errors.company ? 'true' : 'false'}
          aria-describedby={errors.company ? 'company-error' : undefined}
        />
        {errors.company && (
          <p id="company-error" className="mt-1 text-sm text-red-600" role="alert">
            {errors.company.message}
          </p>
        )}
      </div>

      <div>
        <label htmlFor="role" className="block text-sm font-medium text-gray-700 mb-1">
          Role *
        </label>
        <select
          {...register('role')}
          id="role"
          className="w-full px-3 py-2 border border-gray-300 rounded focus:outline-none focus:ring-2 focus:ring-blue-500"
          disabled={isSubmitting}
          aria-invalid={errors.role ? 'true' : 'false'}
          aria-describedby={errors.role ? 'role-error' : undefined}
        >
          <option value="">Select a role</option>
          <option value="USER">User</option>
          <option value="ADMIN">Administrator</option>
        </select>
        {errors.role && (
          <p id="role-error" className="mt-1 text-sm text-red-600" role="alert">
            {errors.role.message}
          </p>
        )}
      </div>

      {mutation.isError && (
        <div className="bg-red-50 border border-red-200 rounded p-3">
          <p className="text-sm text-red-800" role="alert">
            Error: {mutation.error instanceof Error ? mutation.error.message : 'Failed to create account'}
          </p>
        </div>
      )}

      <div className="flex gap-3 pt-2">
        <button
          type="submit"
          disabled={isSubmitting}
          className="flex-1 px-4 py-2 bg-blue-500 text-white rounded hover:bg-blue-600 disabled:bg-gray-300 disabled:cursor-not-allowed"
        >
          {isSubmitting ? 'Creating...' : 'Create Account'}
        </button>
        {onCancel && (
          <button
            type="button"
            onClick={onCancel}
            disabled={isSubmitting}
            className="px-4 py-2 border border-gray-300 rounded hover:bg-gray-50 disabled:bg-gray-100 disabled:cursor-not-allowed"
          >
            Cancel
          </button>
        )}
      </div>
    </form>
  )
}

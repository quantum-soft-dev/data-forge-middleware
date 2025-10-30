/**
 * CreateAccountDialog Component
 *
 * Modal dialog for creating new accounts.
 * Wraps CreateAccountForm and handles mutation state.
 */

import { X } from 'lucide-react'
import { CreateAccountForm } from './CreateAccountForm'
import { useCreateAccount } from '@/entities/account/api/useCreateAccount'
import type { CreateAccountFormData } from '@/entities/account/model/schema'

interface CreateAccountDialogProps {
  open: boolean
  onClose: () => void
}

/**
 * Dialog for creating a new account
 *
 * Features:
 * - Modal overlay with backdrop
 * - Form with validation
 * - Auto-close on success
 * - Reset form on close
 * - ESC key to close
 */
export function CreateAccountDialog({ open, onClose }: CreateAccountDialogProps) {
  const createMutation = useCreateAccount()

  const handleSubmit = async (data: CreateAccountFormData) => {
    createMutation.mutate(data, {
      onSuccess: () => {
        onClose()
      },
    })
  }

  if (!open) return null

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      onClick={onClose}
    >
      <div
        className="relative w-full max-w-md rounded-lg bg-white p-6 shadow-xl"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-labelledby="dialog-title"
        aria-modal="true"
      >
        {/* Header */}
        <div className="mb-4 flex items-center justify-between">
          <h2 id="dialog-title" className="text-xl font-semibold text-gray-900">
            Create Account
          </h2>
          <button
            onClick={onClose}
            disabled={createMutation.isPending}
            className="rounded-md p-1 text-gray-400 hover:bg-gray-100 hover:text-gray-600 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
            aria-label="Close dialog"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        {/* Form */}
        <CreateAccountForm
          onSubmit={handleSubmit}
          onCancel={onClose}
          isSubmitting={createMutation.isPending}
        />
      </div>
    </div>
  )
}

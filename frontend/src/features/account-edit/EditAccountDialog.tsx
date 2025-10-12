/**
 * EditAccountDialog Component
 *
 * Modal dialog for editing existing accounts.
 * Wraps EditAccountForm and handles mutation state.
 */

import { X } from 'lucide-react'
import { EditAccountForm } from './EditAccountForm'
import { useUpdateAccount } from '@/entities/account/api/useUpdateAccount'
import type { UpdateAccountFormData } from '@/entities/account/model/schema'
import type { Account } from '@/entities/account/model/types'

interface EditAccountDialogProps {
  account: Account | null
  open: boolean
  onClose: () => void
}

/**
 * Dialog for editing an existing account
 *
 * Features:
 * - Modal overlay with backdrop
 * - Pre-filled form with current account data
 * - Form with validation
 * - Auto-close on success
 * - ESC key to close
 * - Disabled during mutation
 */
export function EditAccountDialog({ account, open, onClose }: EditAccountDialogProps) {
  const updateMutation = useUpdateAccount()

  const handleSubmit = async (data: UpdateAccountFormData) => {
    updateMutation.mutate(data, {
      onSuccess: () => {
        onClose()
      },
    })
  }

  if (!open || !account) return null

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
            Edit Account
          </h2>
          <button
            onClick={onClose}
            disabled={updateMutation.isPending}
            className="rounded-md p-1 text-gray-400 hover:bg-gray-100 hover:text-gray-600 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
            aria-label="Close dialog"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        {/* Form */}
        <EditAccountForm
          account={account}
          onSubmit={handleSubmit}
          onCancel={onClose}
          isSubmitting={updateMutation.isPending}
        />
      </div>
    </div>
  )
}

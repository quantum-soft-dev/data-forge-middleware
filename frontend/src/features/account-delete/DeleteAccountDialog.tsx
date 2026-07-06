/**
 * DeleteAccountDialog Component
 *
 * Confirmation modal dialog for deleting accounts (soft delete).
 * Shows account details and requires explicit confirmation.
 */

import { X } from 'lucide-react'
import { useDeleteAccount } from '@/entities/account/api/useDeleteAccount'
import type { Account } from '@/entities/account/model/types'

interface DeleteAccountDialogProps {
  account: Account | null
  open: boolean
  onClose: () => void
}

/**
 * Confirmation dialog for deleting an account
 *
 * Features:
 * - Shows account name and email for verification
 * - Requires explicit confirmation (click Delete button)
 * - Destructive styling on delete button (red)
 * - Auto-close on success
 * - ESC key to close
 * - Disabled during mutation
 */
export function DeleteAccountDialog({ account, open, onClose }: DeleteAccountDialogProps) {
  const deleteMutation = useDeleteAccount()

  const handleDelete = () => {
    if (!account) return

    deleteMutation.mutate(account.id, {
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
          <h2 id="dialog-title" className="text-[17px] font-medium tracking-[-0.24px] text-ink">
            Delete Account
          </h2>
          <button
            onClick={onClose}
            disabled={deleteMutation.isPending}
            className="rounded-lg p-1 text-ink-muted hover:bg-secondary hover:text-ink focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-50 disabled:cursor-not-allowed"
            aria-label="Close dialog"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        {/* Content */}
        <div className="mb-6">
          <p className="text-sm text-ink-secondary mb-4">
            Are you sure you want to delete this account? This action will deactivate the account.
          </p>

          {/* Account details for verification */}
          <div className="rounded-md bg-surface-subtle p-4 space-y-2">
            <div>
              <span className="text-xs font-medium text-ink-secondary">Name:</span>
              <p className="text-sm font-semibold text-ink">{account.name}</p>
            </div>
            <div>
              <span className="text-xs font-medium text-ink-secondary">Email:</span>
              <p className="text-sm text-ink-secondary">{account.email}</p>
            </div>
          </div>
        </div>

        {/* Buttons */}
        <div className="flex justify-end gap-3">
          <button
            type="button"
            onClick={onClose}
            disabled={deleteMutation.isPending}
            className="rounded-lg border border-hairline bg-white px-4 py-2 text-sm font-medium text-ink hover:bg-secondary focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={handleDelete}
            disabled={deleteMutation.isPending}
            className="rounded-md bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-red-500 focus:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {deleteMutation.isPending ? 'Deleting...' : 'Delete'}
          </button>
        </div>
      </div>
    </div>
  )
}

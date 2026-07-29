/**
 * DeleteAccountButton component for admin user management.
 *
 * Renders a delete button with confirmation dialog that permanently
 * deletes an account from both PostgreSQL and Auth0.
 *
 * @module features/user-management/ui/DeleteAccountButton
 */

import { useState } from 'react'
import { Trash2, AlertTriangle } from 'lucide-react'
import { useDeleteAccountMutation } from '../api/userMutations'
import type { AccountWithAuthStatus } from '@/entities/account/model/types'

interface DeleteAccountButtonProps {
  account: AccountWithAuthStatus
  onSuccess?: () => void
}

export function DeleteAccountButton({ account, onSuccess }: DeleteAccountButtonProps) {
  const [showConfirmDialog, setShowConfirmDialog] = useState(false)
  const [confirmEmail, setConfirmEmail] = useState('')
  const deleteMutation = useDeleteAccountMutation()

  const handleDelete = async () => {
    if (confirmEmail !== account.email) {
      return
    }

    try {
      await deleteMutation.mutateAsync(account.id)
      setShowConfirmDialog(false)
      onSuccess?.()
    } catch (error) {
      // Error is handled by mutation onError
      console.error('Failed to delete account:', error)
    }
  }

  const isConfirmValid = confirmEmail === account.email

  return (
    <>
      <button
        onClick={() => setShowConfirmDialog(true)}
        className="flex items-center gap-2 rounded-md bg-red-600 px-3 py-1.5 text-sm font-medium text-white transition-colors hover:bg-red-700"
        aria-label={`Delete account ${account.email}`}
        title="Permanently delete this account"
      >
        <Trash2 className="h-4 w-4" />
        Delete Account
      </button>

      {/* Confirmation Dialog */}
      {showConfirmDialog && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-md w-full mx-4">
            <div className="flex items-start gap-3 mb-4">
              <div className="flex-shrink-0 w-10 h-10 rounded-full bg-red-100 flex items-center justify-center">
                <AlertTriangle className="h-6 w-6 text-red-600" />
              </div>
              <div>
                <h2 className="text-lg font-bold text-ink">Delete Account</h2>
                <p className="mt-1 text-sm text-ink-secondary">
                  This action cannot be undone.
                </p>
              </div>
            </div>

            <div className="bg-red-50 border border-red-200 rounded-md p-3 mb-4">
              <p className="text-sm text-red-800">
                <strong>Warning:</strong> This will permanently delete the account and all associated data from both PostgreSQL and Auth0.
              </p>
            </div>

            <div className="mb-4">
              <p className="text-sm text-ink-secondary mb-2">
                Account to delete: <strong>{account.email}</strong>
              </p>
              <p className="text-sm text-ink-secondary mb-2">
                Type the email address to confirm:
              </p>
              <input
                type="text"
                value={confirmEmail}
                onChange={(e) => setConfirmEmail(e.target.value)}
                placeholder={account.email}
                className="w-full px-3 py-2 border border-hairline rounded-md focus:outline-none focus:ring-2 focus:ring-red-500"
                autoFocus
              />
            </div>

            <div className="flex gap-3">
              <button
                type="button"
                onClick={() => {
                  setShowConfirmDialog(false)
                  setConfirmEmail('')
                }}
                disabled={deleteMutation.isPending}
                className="flex-1 px-4 py-2 border border-hairline rounded-md text-sm font-medium text-ink-secondary hover:bg-surface-hover disabled:opacity-50"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleDelete}
                disabled={!isConfirmValid || deleteMutation.isPending}
                className="flex-1 px-4 py-2 bg-red-600 text-white rounded-md text-sm font-medium hover:bg-red-700 disabled:bg-gray-300 disabled:cursor-not-allowed"
              >
                {deleteMutation.isPending ? 'Deleting...' : 'Delete Account'}
              </button>
            </div>

            {deleteMutation.isError && (
              <div className="mt-4 bg-red-50 border border-red-200 rounded p-3">
                <p className="text-sm text-red-800">
                  Error: {deleteMutation.error instanceof Error ? deleteMutation.error.message : 'Failed to delete account'}
                </p>
              </div>
            )}
          </div>
        </div>
      )}
    </>
  )
}

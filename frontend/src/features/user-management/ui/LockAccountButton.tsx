/**
 * LockAccountButton Component
 *
 * Per T044: Button to lock (disable) a user account in Auth0.
 *
 * Features:
 * - Confirmation dialog before locking
 * - Disabled if account already locked or no Auth0 integration
 * - Success/error toast notifications
 * - Accessibility with ARIA labels
 */

import { useState } from 'react'
import { Lock } from 'lucide-react'
import { useLockAccountMutation } from '../api/userMutations'
import type { AccountWithKeycloakStatus } from '@/entities/account/model/types'

interface LockAccountButtonProps {
  account: AccountWithKeycloakStatus
  onSuccess?: () => void
  className?: string
}

export function LockAccountButton({ account, onSuccess, className = '' }: LockAccountButtonProps) {
  const [showConfirmation, setShowConfirmation] = useState(false)
  const lockMutation = useLockAccountMutation()

  const hasAuth0 = !!account.identityProviderUserId
  const isAlreadyLocked = account.isBlocked
  const isDisabled = !hasAuth0 || isAlreadyLocked || lockMutation.isPending

  const handleConfirm = async () => {
    try {
      await lockMutation.mutateAsync(account.id)
      setShowConfirmation(false)
      onSuccess?.()
      // TODO: Show success toast notification
    } catch (error) {
      // Error is handled by mutation, but we can show additional UI feedback
      console.error('Failed to lock account:', error)
      // TODO: Show error toast notification
    }
  }

  const handleCancel = () => {
    setShowConfirmation(false)
  }

  return (
    <>
      <button
        onClick={() => setShowConfirmation(true)}
        disabled={isDisabled}
        className={`flex items-center gap-2 rounded-md px-3 py-1.5 text-sm font-medium transition-colors ${
          isDisabled
            ? 'cursor-not-allowed bg-gray-100 text-gray-400'
            : 'bg-orange-100 text-orange-700 hover:bg-orange-200'
        } ${className}`}
        aria-label={`Lock account for ${account.email}`}
        title={
          !hasAuth0
            ? 'Account does not have Auth0 integration'
            : isAlreadyLocked
            ? 'Account is already locked'
            : 'Lock this account'
        }
      >
        <Lock className="h-4 w-4" />
        Lock Account
      </button>

      {/* Confirmation Dialog */}
      {showConfirmation && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
          onClick={handleCancel}
        >
          <div
            className="w-full max-w-md rounded-lg bg-white p-6 shadow-xl"
            onClick={(e) => e.stopPropagation()}
            role="dialog"
            aria-labelledby="lock-dialog-title"
            aria-modal="true"
          >
            <div className="mb-4">
              <h2
                id="lock-dialog-title"
                className="text-lg font-semibold text-gray-900"
              >
                Lock Account
              </h2>
              <p className="mt-2 text-sm text-gray-600">
                Are you sure you want to lock the account for <strong>{account.email}</strong>?
              </p>
            </div>

            <div className="mb-4 rounded-md bg-orange-50 border border-orange-200 p-3">
              <p className="text-sm text-orange-800">
                ⚠️ The user will not be able to login until the account is unlocked.
                Existing sessions will continue until natural expiration.
              </p>
            </div>

            <div className="flex justify-end gap-3">
              <button
                onClick={handleCancel}
                disabled={lockMutation.isPending}
                className="rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
              >
                Cancel
              </button>
              <button
                onClick={handleConfirm}
                disabled={lockMutation.isPending}
                className="rounded-md bg-orange-600 px-4 py-2 text-sm font-medium text-white hover:bg-orange-700 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {lockMutation.isPending ? 'Locking...' : 'Lock Account'}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}

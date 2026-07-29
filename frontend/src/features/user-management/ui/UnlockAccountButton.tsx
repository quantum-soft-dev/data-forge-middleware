/**
 * UnlockAccountButton Component
 *
 * Per T045: Button to unlock (enable) a user account in Auth0.
 *
 * Features:
 * - Confirmation dialog before unlocking
 * - Disabled if account already unlocked or no Auth0 integration
 * - Success/error toast notifications
 * - Accessibility with ARIA labels
 */

import { useState } from 'react'
import { Unlock } from 'lucide-react'
import { useUnlockAccountMutation } from '../api/userMutations'
import type { AccountWithAuthStatus } from '@/entities/account/model/types'

interface UnlockAccountButtonProps {
  account: AccountWithAuthStatus
  onSuccess?: () => void
  className?: string
}

export function UnlockAccountButton({ account, onSuccess, className = '' }: UnlockAccountButtonProps) {
  const [showConfirmation, setShowConfirmation] = useState(false)
  const unlockMutation = useUnlockAccountMutation()

  const hasAuth0 = !!account.identityProviderUserId
  const isAlreadyUnlocked = !account.isBlocked
  const isDisabled = !hasAuth0 || isAlreadyUnlocked || unlockMutation.isPending

  const handleConfirm = async () => {
    try {
      await unlockMutation.mutateAsync(account.id)
      setShowConfirmation(false)
      onSuccess?.()
      // TODO: Show success toast notification
    } catch (error) {
      // Error is handled by mutation, but we can show additional UI feedback
      console.error('Failed to unlock account:', error)
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
            ? 'cursor-not-allowed bg-surface-subtle text-ink-muted'
            : 'bg-green-100 text-green-700 hover:bg-green-200'
        } ${className}`}
        aria-label={`Unlock account for ${account.email}`}
        title={
          !hasAuth0
            ? 'Account does not have Auth0 integration'
            : isAlreadyUnlocked
            ? 'Account is already unlocked'
            : 'Unlock this account'
        }
      >
        <Unlock className="h-4 w-4" />
        Unlock Account
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
            aria-labelledby="unlock-dialog-title"
            aria-modal="true"
          >
            <div className="mb-4">
              <h2
                id="unlock-dialog-title"
                className="text-[15px] font-medium tracking-[-0.24px] text-ink"
              >
                Unlock Account
              </h2>
              <p className="mt-2 text-sm text-ink-secondary">
                Are you sure you want to unlock the account for <strong>{account.email}</strong>?
              </p>
            </div>

            <div className="mb-4 rounded-md bg-green-50 border border-green-200 p-3">
              <p className="text-sm text-green-800">
                ✓ The user will be able to login again after unlocking.
              </p>
            </div>

            <div className="flex justify-end gap-3">
              <button
                onClick={handleCancel}
                disabled={unlockMutation.isPending}
                className="rounded-md border border-hairline px-4 py-2 text-sm font-medium text-ink-secondary hover:bg-surface-hover disabled:cursor-not-allowed disabled:opacity-50"
              >
                Cancel
              </button>
              <button
                onClick={handleConfirm}
                disabled={unlockMutation.isPending}
                className="rounded-md bg-green-600 px-4 py-2 text-sm font-medium text-white hover:bg-green-700 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {unlockMutation.isPending ? 'Unlocking...' : 'Unlock Account'}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}

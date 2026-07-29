/**
 * ResetPasswordDialog Component
 *
 * Per T058: Dialog for resetting user password to temporary value.
 *
 * Features:
 * - Confirmation dialog with warning about password reset
 * - Displays temporary password ONCE after successful reset (with copy button)
 * - Disabled if account has no Auth0 integration
 * - Success/error toast notifications
 * - Accessibility with ARIA labels and focus trap
 */

import { useState } from 'react'
import { KeyRound, Copy, Check } from 'lucide-react'
import { useResetPasswordMutation } from '../api/userMutations'
import type { AccountWithAuthStatus, ResetPasswordResponse } from '@/entities/account/model/types'

interface ResetPasswordDialogProps {
  account: AccountWithAuthStatus
  onSuccess?: () => void
  onClose?: () => void
  isOpen: boolean
}

export function ResetPasswordDialog({
  account,
  onSuccess,
  onClose,
  isOpen,
}: ResetPasswordDialogProps) {
  const [resetResponse, setResetResponse] = useState<ResetPasswordResponse | null>(null)
  const [copied, setCopied] = useState(false)
  const resetMutation = useResetPasswordMutation()

  const hasAuth0 = !!account.identityProviderUserId

  const handleConfirm = async () => {
    try {
      const response = await resetMutation.mutateAsync(account.id)
      setResetResponse(response)
      onSuccess?.()
      // TODO: Show success toast notification
    } catch (error) {
      console.error('Failed to reset password:', error)
      // TODO: Show error toast notification
    }
  }

  const handleCancel = () => {
    setResetResponse(null)
    setCopied(false)
    onClose?.()
  }

  const handleCopy = async () => {
    if (resetResponse?.passwordResetLink) {
      try {
        await navigator.clipboard.writeText(resetResponse.passwordResetLink)
        setCopied(true)
        setTimeout(() => setCopied(false), 2000)
      } catch (error) {
        console.error('Failed to copy password reset link:', error)
      }
    }
  }

  if (!isOpen) return null

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      onClick={handleCancel}
    >
      <div
        className="w-full max-w-md rounded-lg bg-white p-6 shadow-xl"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-labelledby="reset-password-dialog-title"
        aria-modal="true"
      >
        {!resetResponse ? (
          <>
            {/* Confirmation Step */}
            <div className="mb-4">
              <div className="flex items-center gap-2 mb-2">
                <KeyRound className="h-5 w-5 text-orange-600" />
                <h2
                  id="reset-password-dialog-title"
                  className="text-[15px] font-medium tracking-[-0.24px] text-ink"
                >
                  Reset Password
                </h2>
              </div>
              <p className="mt-2 text-sm text-ink-secondary">
                Are you sure you want to reset the password for <strong>{account.email}</strong>?
              </p>
              <p className="mt-2 text-sm text-ink-secondary italic">
                A password reset link will be generated. Send this link to the user to set a new password.
              </p>
            </div>

            <div className="mb-4 rounded-md bg-orange-50 border border-orange-200 p-3">
              <p className="text-sm text-orange-800">
                ⚠️ The user will receive a one-time password reset link valid for 24 hours.
              </p>
            </div>

            <div className="flex justify-end gap-3">
              <button
                onClick={handleCancel}
                disabled={resetMutation.isPending}
                className="rounded-md border border-hairline px-4 py-2 text-sm font-medium text-ink-secondary hover:bg-surface-hover disabled:cursor-not-allowed disabled:opacity-50"
              >
                Cancel
              </button>
              <button
                onClick={handleConfirm}
                disabled={resetMutation.isPending || !hasAuth0}
                className="rounded-md bg-orange-600 px-4 py-2 text-sm font-medium text-white hover:bg-orange-700 disabled:cursor-not-allowed disabled:opacity-50"
                title={!hasAuth0 ? 'Account does not have Auth0 integration' : 'Reset password'}
              >
                {resetMutation.isPending ? 'Resetting...' : 'Reset Password'}
              </button>
            </div>
          </>
        ) : (
          <>
            {/* Success Step with Password Reset Link */}
            <div className="mb-4">
              <div className="flex items-center gap-2 mb-2">
                <Check className="h-5 w-5 text-green-600" />
                <h2
                  id="reset-password-dialog-title"
                  className="text-[15px] font-medium tracking-[-0.24px] text-ink"
                >
                  Password Reset Link Generated
                </h2>
              </div>
              <p className="mt-2 text-sm text-ink-secondary">
                A password reset link has been generated for <strong>{account.email}</strong>.
              </p>
            </div>

            {/* Password Reset Link Display */}
            <div className="mb-4 rounded-md bg-red-50 border border-red-300 p-4">
              <p className="text-sm font-semibold text-red-900 mb-2">
                ⚠️ Save this link now. Send it to the user to reset their password.
              </p>
              <div className="flex items-center gap-2 bg-white rounded border border-red-200 p-3">
                <code className="flex-1 font-mono text-xs text-ink select-all break-all">
                  {resetResponse.passwordResetLink}
                </code>
                <button
                  onClick={handleCopy}
                  className="flex-shrink-0 rounded px-2 py-1 text-sm font-medium text-blue-600 hover:bg-blue-50 transition-colors"
                  aria-label="Copy password reset link to clipboard"
                >
                  {copied ? (
                    <Check className="h-4 w-4 text-green-600" />
                  ) : (
                    <Copy className="h-4 w-4" />
                  )}
                </button>
              </div>
              <p className="text-xs text-ink-secondary mt-2">
                Expires: {new Date(resetResponse.expiresAt).toLocaleDateString()} at {new Date(resetResponse.expiresAt).toLocaleTimeString()} (24 hours)
              </p>
            </div>

            <div className="mb-4 rounded-md bg-blue-50 border border-blue-200 p-3">
              <p className="text-sm text-blue-800">
                ℹ️ This is a one-time use link. The user will be directed to Auth0 to set a new password.
              </p>
            </div>

            <div className="flex justify-end">
              <button
                onClick={handleCancel}
                className="rounded-md bg-gray-600 px-4 py-2 text-sm font-medium text-white hover:bg-gray-700"
              >
                Close
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  )
}

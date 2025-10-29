/**
 * ResetPasswordDialog Component
 *
 * Per T058: Dialog for resetting user password to temporary value.
 *
 * Features:
 * - Confirmation dialog with warning about password reset
 * - Displays temporary password ONCE after successful reset (with copy button)
 * - Disabled if account has no Keycloak integration
 * - Success/error toast notifications
 * - Accessibility with ARIA labels and focus trap
 */

import { useState } from 'react'
import { KeyRound, Copy, Check } from 'lucide-react'
import { useResetPasswordMutation } from '../api/userMutations'
import type { AccountWithKeycloakStatus, ResetPasswordResponse } from '@/entities/account/model/types'

interface ResetPasswordDialogProps {
  account: AccountWithKeycloakStatus
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

  const hasKeycloak = !!account.keycloakUserId

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
    if (resetResponse?.temporaryPassword) {
      try {
        await navigator.clipboard.writeText(resetResponse.temporaryPassword)
        setCopied(true)
        setTimeout(() => setCopied(false), 2000)
      } catch (error) {
        console.error('Failed to copy password:', error)
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
                  className="text-lg font-semibold text-gray-900"
                >
                  Reset Password
                </h2>
              </div>
              <p className="mt-2 text-sm text-gray-600">
                Are you sure you want to reset the password for <strong>{account.email}</strong>?
              </p>
              <p className="mt-2 text-sm text-gray-500 italic">
                A temporary password will be generated. The user must change it on their next login.
              </p>
            </div>

            <div className="mb-4 rounded-md bg-orange-50 border border-orange-200 p-3">
              <p className="text-sm text-orange-800">
                ⚠️ The user's current password will be invalidated immediately.
              </p>
            </div>

            <div className="flex justify-end gap-3">
              <button
                onClick={handleCancel}
                disabled={resetMutation.isPending}
                className="rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
              >
                Cancel
              </button>
              <button
                onClick={handleConfirm}
                disabled={resetMutation.isPending || !hasKeycloak}
                className="rounded-md bg-orange-600 px-4 py-2 text-sm font-medium text-white hover:bg-orange-700 disabled:cursor-not-allowed disabled:opacity-50"
                title={!hasKeycloak ? 'Account does not have Keycloak integration' : 'Reset password'}
              >
                {resetMutation.isPending ? 'Resetting...' : 'Reset Password'}
              </button>
            </div>
          </>
        ) : (
          <>
            {/* Success Step with Temporary Password */}
            <div className="mb-4">
              <div className="flex items-center gap-2 mb-2">
                <Check className="h-5 w-5 text-green-600" />
                <h2
                  id="reset-password-dialog-title"
                  className="text-lg font-semibold text-gray-900"
                >
                  Password Reset Successfully
                </h2>
              </div>
              <p className="mt-2 text-sm text-gray-600">
                The password has been reset for <strong>{account.email}</strong>.
              </p>
            </div>

            {/* Temporary Password Display */}
            <div className="mb-4 rounded-md bg-red-50 border border-red-300 p-4">
              <p className="text-sm font-semibold text-red-900 mb-2">
                ⚠️ Save this password now. It will NOT be shown again.
              </p>
              <div className="flex items-center gap-2 bg-white rounded border border-red-200 p-3">
                <code className="flex-1 font-mono text-sm text-gray-900 select-all">
                  {resetResponse.temporaryPassword}
                </code>
                <button
                  onClick={handleCopy}
                  className="rounded px-2 py-1 text-sm font-medium text-blue-600 hover:bg-blue-50 transition-colors"
                  aria-label="Copy password to clipboard"
                >
                  {copied ? (
                    <Check className="h-4 w-4 text-green-600" />
                  ) : (
                    <Copy className="h-4 w-4" />
                  )}
                </button>
              </div>
              <p className="text-xs text-gray-600 mt-2">
                Expires: {new Date(resetResponse.expiresAt).toLocaleDateString()} (30 days)
              </p>
            </div>

            <div className="mb-4 rounded-md bg-blue-50 border border-blue-200 p-3">
              <p className="text-sm text-blue-800">
                ℹ️ The user will be required to change this password on their next login.
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

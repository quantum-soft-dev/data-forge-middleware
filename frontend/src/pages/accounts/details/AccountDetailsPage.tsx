/**
 * AccountDetailsPage - User Management
 *
 * Per T046: Account details page with lock/unlock actions.
 *
 * Features:
 * - Display AccountCard with full details
 * - Lock/Unlock buttons (conditionally shown based on status)
 * - Admin action log list (audit trail)
 * - Back navigation to accounts list
 */

import { useParams, useNavigate } from '@tanstack/react-router'
import { ArrowLeft } from 'lucide-react'
import { Header } from '@/widgets/header/Header'
import { AccountCard } from '@/entities/account/ui/AccountCard'
import { LockAccountButton } from '@/features/user-management/ui/LockAccountButton'
import { UnlockAccountButton } from '@/features/user-management/ui/UnlockAccountButton'
import { ResetPasswordDialog } from '@/features/user-management/ui/ResetPasswordDialog'
import { AdminActionLogList } from '@/features/user-management/ui/AdminActionLogList'
import { useAccountQuery } from '@/features/user-management/api/userQueries'
import { useState } from 'react'
import { KeyRound } from 'lucide-react'

export default function AccountDetailsPage() {
  const { id } = useParams({ from: '/admin/users/$id' })
  const navigate = useNavigate()
  const [showResetPasswordDialog, setShowResetPasswordDialog] = useState(false)

  const { data: account, isLoading, isError, error } = useAccountQuery(id)

  const handleBack = () => {
    navigate({ to: '/admin/users' })
  }

  const handleLockSuccess = () => {
    // Query will be invalidated by mutation, data will refetch automatically
    console.log('Account locked successfully')
  }

  const handleUnlockSuccess = () => {
    // Query will be invalidated by mutation, data will refetch automatically
    console.log('Account unlocked successfully')
  }

  const handleResetPasswordSuccess = () => {
    // Query will be invalidated by mutation, data will refetch automatically
    console.log('Password reset successfully')
  }

  if (isLoading) {
    return (
      <div className="min-h-screen bg-gray-50">
        <Header />
        <main className="mx-auto max-w-5xl px-4 py-8 sm:px-6 lg:px-8">
          <div className="flex items-center justify-center py-12">
            <div className="h-8 w-8 animate-spin rounded-full border-4 border-gray-200 border-t-blue-600" />
            <span className="ml-3 text-sm text-gray-600">Loading account details...</span>
          </div>
        </main>
      </div>
    )
  }

  if (isError || !account) {
    return (
      <div className="min-h-screen bg-gray-50">
        <Header />
        <main className="mx-auto max-w-5xl px-4 py-8 sm:px-6 lg:px-8">
          <div className="rounded-lg border border-red-200 bg-red-50 p-4">
            <p className="text-sm font-medium text-red-800">
              {error instanceof Error ? error.message : 'Failed to load account details'}
            </p>
          </div>
        </main>
      </div>
    )
  }

  const hasKeycloak = !!account.keycloakUserId
  const isLocked = !account.keycloakEnabled

  return (
    <div className="min-h-screen bg-gray-50">
      <Header />

      <main className="mx-auto max-w-5xl px-4 py-8 sm:px-6 lg:px-8">
        {/* Page header with back button */}
        <div className="mb-8">
          <button
            onClick={handleBack}
            className="mb-4 flex items-center gap-2 text-sm text-gray-600 hover:text-gray-900"
          >
            <ArrowLeft className="h-4 w-4" />
            Back to User Management
          </button>
          <div className="flex items-start justify-between">
            <div>
              <h1 className="text-3xl font-bold text-gray-900">Account Details</h1>
              <p className="mt-2 text-sm text-gray-600">
                View and manage account information
              </p>
            </div>

            {/* Action Buttons */}
            {hasKeycloak && (
              <div className="flex gap-3">
                <button
                  onClick={() => setShowResetPasswordDialog(true)}
                  className="flex items-center gap-2 rounded-md bg-orange-100 px-3 py-1.5 text-sm font-medium text-orange-700 transition-colors hover:bg-orange-200"
                  aria-label={`Reset password for ${account.email}`}
                  title="Reset password to temporary value"
                >
                  <KeyRound className="h-4 w-4" />
                  Reset Password
                </button>
                {isLocked ? (
                  <UnlockAccountButton account={account} onSuccess={handleUnlockSuccess} />
                ) : (
                  <LockAccountButton account={account} onSuccess={handleLockSuccess} />
                )}
              </div>
            )}
          </div>
        </div>

        {/* Account Details Card */}
        <div className="mb-8">
          <AccountCard account={account} />
        </div>

        {/* Keycloak Integration Info */}
        {!hasKeycloak && (
          <div className="mb-8 rounded-lg border border-yellow-200 bg-yellow-50 p-4">
            <h3 className="text-sm font-medium text-yellow-900 mb-1">
              No Keycloak Integration
            </h3>
            <p className="text-sm text-yellow-800">
              This account was created without Keycloak integration. Lock/unlock actions
              are not available.
            </p>
          </div>
        )}

        {/* Admin Action Log Section */}
        <div>
          <h2 className="text-xl font-bold text-gray-900 mb-4">Audit Log</h2>
          <p className="text-sm text-gray-600 mb-4">
            History of administrative actions performed on this account
          </p>
          <AdminActionLogList accountId={id} />
        </div>
      </main>

      {/* Reset Password Dialog */}
      <ResetPasswordDialog
        account={account}
        isOpen={showResetPasswordDialog}
        onSuccess={handleResetPasswordSuccess}
        onClose={() => setShowResetPasswordDialog(false)}
      />
    </div>
  )
}

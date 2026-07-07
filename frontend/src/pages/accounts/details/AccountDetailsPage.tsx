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
import { ArrowLeft, Globe } from 'lucide-react'
import { Header } from '@/widgets/header/Header'
import { AccountCard } from '@/entities/account/ui/AccountCard'
import { LockAccountButton } from '@/features/user-management/ui/LockAccountButton'
import { UnlockAccountButton } from '@/features/user-management/ui/UnlockAccountButton'
import { ResetPasswordDialog } from '@/features/user-management/ui/ResetPasswordDialog'
import { DeleteAccountButton } from '@/features/user-management/ui/DeleteAccountButton'
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

  const handleManageSites = () => {
    navigate({ to: '/admin/users/$id/sites', params: { id } })
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

  const handleDeleteSuccess = () => {
    // Navigate back to user list after successful deletion
    navigate({ to: '/admin/users' })
  }

  if (isLoading) {
    return (
      <div className="min-h-screen bg-white">
        <Header />
        <main className="mx-auto max-w-[1120px] px-6 py-6">
          <div className="flex items-center justify-center py-12">
            <div className="h-8 w-8 animate-spin rounded-full border-4 border-secondary border-t-brand" />
            <span className="ml-3 text-sm text-ink-secondary">Loading account details...</span>
          </div>
        </main>
      </div>
    )
  }

  if (isError || !account) {
    return (
      <div className="min-h-screen bg-white">
        <Header />
        <main className="mx-auto max-w-[1120px] px-6 py-6">
          <div className="rounded-lg border border-danger-border bg-danger-bg p-4">
            <p className="text-sm font-medium text-danger-text">
              {error instanceof Error ? error.message : 'Failed to load account details'}
            </p>
          </div>
        </main>
      </div>
    )
  }

  const hasAuth0 = !!account.identityProviderUserId
  const isLocked = account.isBlocked
  const isAdmin = !account.company // Admins don't have company

  return (
    <div className="min-h-screen bg-white">
      <Header />

      <main className="mx-auto max-w-[1120px] px-6 py-6">
        {/* Page header with back button */}
        <div className="mb-8">
          <button
            onClick={handleBack}
            className="mb-4 flex items-center gap-2 text-sm text-ink-secondary hover:text-ink"
          >
            <ArrowLeft className="h-4 w-4" />
            Back to User Management
          </button>
          <div className="flex items-start justify-between">
            <div>
              <h1 className="text-[22px] font-medium leading-[1.1] tracking-[-0.33px] text-ink">Account Details</h1>
              <p className="mt-2 text-sm text-ink-secondary">
                View and manage account information
              </p>
            </div>

            {/* Action Buttons */}
            {hasAuth0 && (
              <div className="flex gap-3">
                <button
                  onClick={() => setShowResetPasswordDialog(true)}
                  className="flex items-center gap-2 rounded-lg border border-hairline bg-white px-3 py-1.5 text-sm font-medium text-ink transition-colors hover:bg-secondary"
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
                <DeleteAccountButton account={account} onSuccess={handleDeleteSuccess} />
              </div>
            )}
          </div>
        </div>

        {/* Account Details Card */}
        <div className="mb-8">
          <AccountCard account={account} />
        </div>

        {/* Auth0 Integration Info */}
        {!hasAuth0 && (
          <div className="mb-8 rounded-lg border border-yellow-200 bg-yellow-50 p-4">
            <h3 className="text-sm font-medium text-yellow-900 mb-1">
              No Auth0 Integration
            </h3>
            <p className="text-sm text-yellow-800">
              This account was created without Auth0 integration. Lock/unlock/delete actions
              are not available.
            </p>
          </div>
        )}

        {/* Sites Section - Only show for non-admin accounts */}
        {!isAdmin && (
          <div className="mb-8">
            <p className="text-sm text-ink-secondary mb-4">
              Manage sites for this user account
            </p>
            <button
              onClick={handleManageSites}
              className="flex items-center gap-2 rounded-lg bg-brand px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-brand-hover focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"
            >
              <Globe className="h-4 w-4" />
              Manage Sites
            </button>
          </div>
        )}

        {/* Admin Action Log Section */}
        <div>
          <h2 className="text-xl font-bold text-ink mb-4">Audit Log</h2>
          <p className="text-sm text-ink-secondary mb-4">
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

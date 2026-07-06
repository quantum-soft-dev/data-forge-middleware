/**
 * CreateAccountPage - Admin User Management
 *
 * Dedicated page for creating new accounts with Auth0 integration.
 * Per T028: Page layout with CreateAccountForm and navigation.
 *
 * Features:
 * - Full page layout with header
 * - Back navigation to accounts list
 * - Uses Auth0-integrated CreateAccountForm from user-management
 * - Displays password reset link on success
 */

import { useNavigate } from '@tanstack/react-router'
import { ArrowLeft } from 'lucide-react'
import { Header } from '@/widgets/header/Header'
import { CreateAccountForm } from '@/features/user-management/ui/CreateAccountForm'

export default function CreateAccountPage() {
  const navigate = useNavigate()

  const handleSuccess = () => {
    // After showing password reset link, navigate back to list
    // The CreateAccountForm handles reset link display internally
    setTimeout(() => {
      navigate({ to: '/admin/users' })
    }, 5000) // 5 second delay to allow user to save reset link
  }

  const handleCancel = () => {
    navigate({ to: '/admin/users' })
  }

  return (
    <div className="min-h-screen bg-surface-hover">
      <Header />

      <main className="mx-auto max-w-3xl px-4 py-8 sm:px-6 lg:px-8">
        {/* Page header with back button */}
        <div className="mb-8">
          <button
            onClick={handleCancel}
            className="mb-4 flex items-center gap-2 text-sm text-ink-secondary hover:text-ink"
          >
            <ArrowLeft className="h-4 w-4" />
            Back to User Management
          </button>
          <div>
            <h1 className="text-[22px] font-medium leading-[1.1] tracking-[-0.33px] text-ink">Create New Account</h1>
            <p className="mt-2 text-sm text-ink-secondary">
              Create a new user account with Auth0 authentication integration
            </p>
          </div>
        </div>

        {/* Form card */}
        <div className="rounded-lg bg-white p-6 shadow-sm border border-separator">
          <CreateAccountForm
            onSuccess={handleSuccess}
            onCancel={handleCancel}
          />
        </div>

        {/* Info panel */}
        <div className="mt-6 rounded-lg bg-blue-50 border border-blue-200 p-4">
          <h3 className="text-sm font-medium text-blue-900 mb-2">
            Account Creation Process
          </h3>
          <ul className="text-sm text-blue-800 space-y-1 list-disc list-inside">
            <li>User will be created in both the database and Auth0</li>
            <li>A password reset link will be generated (valid for 24 hours)</li>
            <li>Send the reset link to the user to set their password</li>
            <li>Account will be enabled by default</li>
          </ul>
        </div>
      </main>
    </div>
  )
}

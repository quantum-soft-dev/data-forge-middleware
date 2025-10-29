/**
 * CreateAccountPage - Admin User Management
 *
 * Dedicated page for creating new accounts with Keycloak integration.
 * Per T028: Page layout with CreateAccountForm and navigation.
 *
 * Features:
 * - Full page layout with header
 * - Back navigation to accounts list
 * - Uses Keycloak-integrated CreateAccountForm from user-management
 * - Displays temporary password on success
 */

import { useNavigate } from '@tanstack/react-router'
import { ArrowLeft } from 'lucide-react'
import { Header } from '@/widgets/header/Header'
import { CreateAccountForm } from '@/features/user-management/ui/CreateAccountForm'

export default function CreateAccountPage() {
  const navigate = useNavigate()

  const handleSuccess = () => {
    // After showing temporary password, navigate back to list
    // The CreateAccountForm handles password display internally
    setTimeout(() => {
      navigate({ to: '/accounts' })
    }, 5000) // 5 second delay to allow user to save password
  }

  const handleCancel = () => {
    navigate({ to: '/accounts' })
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <Header />

      <main className="mx-auto max-w-3xl px-4 py-8 sm:px-6 lg:px-8">
        {/* Page header with back button */}
        <div className="mb-8">
          <button
            onClick={handleCancel}
            className="mb-4 flex items-center gap-2 text-sm text-gray-600 hover:text-gray-900"
          >
            <ArrowLeft className="h-4 w-4" />
            Back to Accounts
          </button>
          <div>
            <h1 className="text-3xl font-bold text-gray-900">Create New Account</h1>
            <p className="mt-2 text-sm text-gray-600">
              Create a new user account with Keycloak authentication integration
            </p>
          </div>
        </div>

        {/* Form card */}
        <div className="rounded-lg bg-white p-6 shadow-sm border border-gray-200">
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
            <li>User will be created in both the database and Keycloak</li>
            <li>A temporary password will be generated (valid for 30 days)</li>
            <li>User must change password on first login</li>
            <li>Account will be enabled by default</li>
          </ul>
        </div>
      </main>
    </div>
  )
}

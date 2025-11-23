/**
 * AccountCard Component
 *
 * Per T029: Display account information in a card format.
 * Shows email, name, status badges, and Keycloak integration status.
 *
 * Features:
 * - Account details (email, name, phone, company)
 * - Status badges (active, keycloak, password)
 * - Timestamps (created, updated, last login)
 * - Keycloak user ID display
 * - Responsive layout
 */

import { Mail, User, Phone, Building, Calendar, Key } from 'lucide-react'
import type { AccountWithKeycloakStatus } from '../model/types'

interface AccountCardProps {
  account: AccountWithKeycloakStatus
  className?: string
}

export function AccountCard({ account, className = '' }: AccountCardProps) {
  const formatDate = (dateString: string | null) => {
    if (!dateString) return 'Never'
    return new Date(dateString).toLocaleString('en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    })
  }

  return (
    <div className={`rounded-lg border border-gray-200 bg-white p-6 shadow-sm ${className}`}>
      {/* Header with status badges */}
      <div className="mb-4 flex items-start justify-between">
        <div className="flex-1">
          <h3 className="text-lg font-semibold text-gray-900">{account.name}</h3>
          <p className="text-sm text-gray-500">{account.email}</p>
        </div>
      </div>

      {/* Status Badges */}
      <div className="mb-4 flex gap-2">
        {account.isActive && (
          <span className="inline-flex items-center rounded-full bg-green-100 px-2.5 py-0.5 text-xs font-medium text-green-800">
            Active
          </span>
        )}
        {account.identityProviderUserId && (
          <span className="inline-flex items-center rounded-full bg-blue-100 px-2.5 py-0.5 text-xs font-medium text-blue-800">
            Auth0
          </span>
        )}
        {account.isBlocked && (
          <span className="inline-flex items-center rounded-full bg-red-100 px-2.5 py-0.5 text-xs font-medium text-red-800">
            Blocked
          </span>
        )}
      </div>

      {/* Account Details Grid */}
      <div className="grid gap-4 sm:grid-cols-2">
        {/* Email */}
        <div className="flex items-start gap-2">
          <Mail className="mt-0.5 h-4 w-4 text-gray-400" />
          <div className="min-w-0 flex-1">
            <p className="text-xs font-medium text-gray-500">Email</p>
            <p className="truncate text-sm text-gray-900">{account.email}</p>
          </div>
        </div>

        {/* Name */}
        <div className="flex items-start gap-2">
          <User className="mt-0.5 h-4 w-4 text-gray-400" />
          <div className="min-w-0 flex-1">
            <p className="text-xs font-medium text-gray-500">Name</p>
            <p className="truncate text-sm text-gray-900">{account.name}</p>
          </div>
        </div>

        {/* Phone */}
        {account.phone && (
          <div className="flex items-start gap-2">
            <Phone className="mt-0.5 h-4 w-4 text-gray-400" />
            <div className="min-w-0 flex-1">
              <p className="text-xs font-medium text-gray-500">Phone</p>
              <p className="truncate text-sm text-gray-900">{account.phone}</p>
            </div>
          </div>
        )}

        {/* Company */}
        {account.company && (
          <div className="flex items-start gap-2">
            <Building className="mt-0.5 h-4 w-4 text-gray-400" />
            <div className="min-w-0 flex-1">
              <p className="text-xs font-medium text-gray-500">Company</p>
              <p className="truncate text-sm text-gray-900">{account.company}</p>
            </div>
          </div>
        )}

        {/* Auth0 User ID */}
        {account.identityProviderUserId && (
          <div className="flex items-start gap-2 sm:col-span-2">
            <Key className="mt-0.5 h-4 w-4 text-gray-400" />
            <div className="min-w-0 flex-1">
              <p className="text-xs font-medium text-gray-500">Auth0 User ID</p>
              <p className="truncate font-mono text-xs text-gray-700">{account.identityProviderUserId}</p>
            </div>
          </div>
        )}
      </div>

      {/* Timestamps */}
      <div className="mt-4 border-t border-gray-200 pt-4">
        <div className="grid gap-3 text-xs sm:grid-cols-3">
          <div className="flex items-center gap-1.5">
            <Calendar className="h-3.5 w-3.5 text-gray-400" />
            <div>
              <p className="font-medium text-gray-500">Created</p>
              <p className="text-gray-700">{formatDate(account.createdAt)}</p>
            </div>
          </div>

          <div className="flex items-center gap-1.5">
            <Calendar className="h-3.5 w-3.5 text-gray-400" />
            <div>
              <p className="font-medium text-gray-500">Last Login</p>
              <p className="text-gray-700">{formatDate(account.lastLogin)}</p>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

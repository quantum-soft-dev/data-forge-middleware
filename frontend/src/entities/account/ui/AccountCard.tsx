/**
 * AccountCard Component
 *
 * Per T029: Display account information in a card format.
 * Shows email, name, status badges, and Auth0 integration status.
 *
 * Features:
 * - Account details (email, name, phone, company)
 * - Status badges (active, Auth0, blocked)
 * - Timestamps (created, updated, last login)
 * - Auth0 user ID display
 * - Responsive layout
 */

import { Mail, User, Phone, Building, Calendar, Key } from 'lucide-react'
import { cn } from '@/shared/lib/utils'
import type { AccountWithAuthStatus } from '../model/types'

interface AccountCardProps {
  account: AccountWithAuthStatus
  className?: string
}

export function AccountCard({ account, className }: AccountCardProps) {
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
    <div className={cn('rounded-lg bg-white p-6 shadow-panel', className)}>
      {/* Header with status badges */}
      <div className="mb-4 flex items-start justify-between">
        <div className="flex-1">
          <h3 className="text-[15px] font-medium tracking-[-0.24px] text-ink">{account.name}</h3>
          <p className="text-sm text-ink-secondary">{account.email}</p>
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
          <Mail className="mt-0.5 h-4 w-4 text-ink-muted" />
          <div className="min-w-0 flex-1">
            <p className="text-xs font-medium text-ink-secondary">Email</p>
            <p className="truncate text-sm text-ink">{account.email}</p>
          </div>
        </div>

        {/* Name */}
        <div className="flex items-start gap-2">
          <User className="mt-0.5 h-4 w-4 text-ink-muted" />
          <div className="min-w-0 flex-1">
            <p className="text-xs font-medium text-ink-secondary">Name</p>
            <p className="truncate text-sm text-ink">{account.name}</p>
          </div>
        </div>

        {/* Phone */}
        {account.phone && (
          <div className="flex items-start gap-2">
            <Phone className="mt-0.5 h-4 w-4 text-ink-muted" />
            <div className="min-w-0 flex-1">
              <p className="text-xs font-medium text-ink-secondary">Phone</p>
              <p className="truncate text-sm text-ink">{account.phone}</p>
            </div>
          </div>
        )}

        {/* Company */}
        {account.company && (
          <div className="flex items-start gap-2">
            <Building className="mt-0.5 h-4 w-4 text-ink-muted" />
            <div className="min-w-0 flex-1">
              <p className="text-xs font-medium text-ink-secondary">Company</p>
              <p className="truncate text-sm text-ink">{account.company}</p>
            </div>
          </div>
        )}

        {/* Auth0 User ID */}
        {account.identityProviderUserId && (
          <div className="flex items-start gap-2 sm:col-span-2">
            <Key className="mt-0.5 h-4 w-4 text-ink-muted" />
            <div className="min-w-0 flex-1">
              <p className="text-xs font-medium text-ink-secondary">Auth0 User ID</p>
              <p className="truncate font-mono text-xs text-ink-secondary">{account.identityProviderUserId}</p>
            </div>
          </div>
        )}
      </div>

      {/* Timestamps */}
      <div className="mt-4 border-t border-separator pt-4">
        <div className="grid gap-3 text-xs sm:grid-cols-3">
          <div className="flex items-center gap-1.5">
            <Calendar className="h-3.5 w-3.5 text-ink-muted" />
            <div>
              <p className="font-medium text-ink-secondary">Created</p>
              <p className="text-ink-secondary">{formatDate(account.createdAt)}</p>
            </div>
          </div>

          <div className="flex items-center gap-1.5">
            <Calendar className="h-3.5 w-3.5 text-ink-muted" />
            <div>
              <p className="font-medium text-ink-secondary">Last Login</p>
              <p className="text-ink-secondary">{formatDate(account.lastLogin)}</p>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

/**
 * Account Entity Types
 *
 * Domain model for account management with Auth0 integration.
 * Per data-model.md: Account entity with status tracking and Auth0 correlation.
 */

export type AccountStatus = 'active' | 'inactive'

export interface Account {
  id: string
  name: string
  email: string
  phone: string | null
  company: string | null
  status: AccountStatus
  identityProviderUserId: string | null // Auth0 user ID (format: auth0|xxx)
  isActive: boolean // Business logic status
  createdAt: string // ISO 8601 datetime
  updatedAt: string // ISO 8601 datetime
}

export interface AccountWithAuthStatus extends Account {
  isBlocked: boolean // From Auth0 user.blocked
  lastLogin: string | null // ISO 8601
}

export interface AccountFilters {
  search?: string
  status?: AccountStatus
  page?: number // 1-indexed for UI
  size?: number
}

export interface AccountListResponse {
  content: Account[]
  page: number // 0-indexed from API
  size: number
  totalElements: number
  totalPages: number
}

export interface CreateAccountRequest {
  email: string
  name: string
  phone?: string
  company?: string
  role: string // Auth0 role assignment
}

/**
 * Backend response from POST /api/v1/accounts
 * Returns flat account data with optional password reset URL
 */
export interface CreateAccountResponse {
  id: string
  email: string
  name: string
  phone: string | null
  company: string | null
  isActive: boolean
  identityProviderUserId: string | null
  temporaryPassword: string | null
  passwordResetUrl: string | null // Auth0 password reset link (24-hour expiry)
  createdAt: string
}

export interface ResetPasswordResponse {
  accountId: string
  email: string
  passwordResetLink: string // Auth0 password change ticket URL (one-time use, 24 hours)
  expiresAt: string // ISO 8601 timestamp (24 hours from now)
}

export interface AdminActionLog {
  id: number
  actionType: 'CREATE_ACCOUNT' | 'LOCK_ACCOUNT' | 'UNLOCK_ACCOUNT' | 'RESET_PASSWORD'
  targetAccountId: string
  adminAccountId: string | null
  status: 'SUCCESS' | 'FAILED'
  errorMessage?: string
  ipAddress?: string
  userAgent?: string
  createdAt: string
}

export interface AdminActionLogListResponse {
  content: AdminActionLog[]
  page: number // 0-indexed from API
  size: number
  totalElements: number
  totalPages: number
}

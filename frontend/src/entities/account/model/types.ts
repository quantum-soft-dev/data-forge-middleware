/**
 * Account Entity Types
 *
 * Domain model for account management with Keycloak integration.
 * Per data-model.md: Account entity with status tracking and Keycloak correlation.
 */

export type AccountStatus = 'active' | 'inactive'

export interface Account {
  id: string
  name: string
  email: string
  phone: string | null
  company: string | null
  status: AccountStatus
  keycloakUserId: string | null // Keycloak user UUID (null for legacy accounts)
  isActive: boolean // Business logic status
  createdAt: string // ISO 8601 datetime
  updatedAt: string // ISO 8601 datetime
}

export interface AccountWithKeycloakStatus extends Account {
  keycloakEnabled: boolean // From Keycloak user.enabled
  passwordTemporary: boolean // From Keycloak credentials
  passwordExpiresAt: string | null // From Keycloak (if temporary)
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
  role: string // Keycloak role assignment
}

export interface CreateAccountResponse {
  account: AccountWithKeycloakStatus
  temporaryPassword: string
}

export interface ResetPasswordResponse {
  accountId: string
  temporaryPassword: string
  expiresAt: string // 30 days from now
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

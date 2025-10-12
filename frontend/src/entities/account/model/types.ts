/**
 * Account Entity Types
 *
 * Domain model for account management.
 * Per data-model.md: Account entity with status tracking.
 */

export type AccountStatus = 'active' | 'inactive'

export interface Account {
  id: string
  name: string
  email: string
  phone: string | null
  company: string | null
  status: AccountStatus
  createdAt: string // ISO 8601 datetime
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

/**
 * Subscriber Entity Types
 *
 * Domain model for subscriber management.
 * Per data-model.md: Subscriber entity with status tracking.
 */

export type SubscriberStatus = 'active' | 'inactive'

export interface Subscriber {
  id: string
  name: string
  email: string
  phone: string | null
  company: string | null
  status: SubscriberStatus
  createdAt: string // ISO 8601 datetime
}

export interface SubscriberFilters {
  search?: string
  status?: SubscriberStatus
  page?: number // 1-indexed for UI
  size?: number
}

export interface SubscriberListResponse {
  content: Subscriber[]
  page: number // 0-indexed from API
  size: number
  totalElements: number
  totalPages: number
}

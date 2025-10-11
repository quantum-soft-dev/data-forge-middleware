/**
 * Account API Client (Subscriber entity)
 *
 * HTTP client for account endpoints.
 * Uses axios instance from shared/api/client.
 * Maps backend Account model to frontend Subscriber interface.
 */

import { apiClient } from '@/shared/api/client'
import type { SubscriberFilters, SubscriberListResponse, Subscriber } from '../model/types'
import type { CreateSubscriberFormData } from '../model/schema'

/**
 * Fetch accounts with pagination and filtering
 * Backend endpoint: GET /api/admin/accounts
 *
 * @param filters - Search, status, and pagination params
 * @returns Paginated list of accounts (mapped to Subscriber interface)
 */
export async function fetchSubscribers(
  filters: SubscriberFilters
): Promise<SubscriberListResponse> {
  const params = new URLSearchParams()

  // Convert 1-indexed page to 0-indexed for API
  if (filters.page !== undefined) {
    params.append('page', String(filters.page - 1))
  }

  if (filters.size !== undefined) {
    params.append('size', String(filters.size))
  }

  if (filters.search) {
    params.append('search', filters.search)
  }

  if (filters.status) {
    params.append('status', filters.status)
  }

  const response = await apiClient.get<SubscriberListResponse>(
    `/admin/accounts?${params.toString()}`
  )

  return response.data
}

/**
 * Create a new account
 * Backend endpoint: POST /api/admin/accounts
 *
 * @param data - Account data (name, email)
 * @returns Created account with ID and metadata (mapped to Subscriber interface)
 */
export async function createSubscriber(
  data: CreateSubscriberFormData
): Promise<Subscriber> {
  const response = await apiClient.post<Subscriber>('/admin/accounts', data)
  return response.data
}

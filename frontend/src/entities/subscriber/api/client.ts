/**
 * Subscriber API Client
 *
 * HTTP client for subscriber endpoints.
 * Uses axios instance from shared/api/client.
 */

import { apiClient } from '@/shared/api/client'
import type { SubscriberFilters, SubscriberListResponse } from '../model/types'

/**
 * Fetch subscribers with pagination and filtering
 *
 * @param filters - Search, status, and pagination params
 * @returns Paginated list of subscribers
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
    `/admin/subscribers?${params.toString()}`
  )

  return response.data
}

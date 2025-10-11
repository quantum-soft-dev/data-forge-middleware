/**
 * Subscriber API Client
 *
 * HTTP client for subscriber endpoints.
 * Uses axios instance from shared/api/client.
 */

import { apiClient } from '@/shared/api/client'
import type { SubscriberFilters, SubscriberListResponse, Subscriber } from '../model/types'
import type { CreateSubscriberFormData } from '../model/schema'

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

/**
 * Create a new subscriber
 *
 * @param data - Subscriber data (name, email, phone, company)
 * @returns Created subscriber with ID and metadata
 */
export async function createSubscriber(
  data: CreateSubscriberFormData
): Promise<Subscriber> {
  const response = await apiClient.post<Subscriber>('/admin/subscribers', data)
  return response.data
}

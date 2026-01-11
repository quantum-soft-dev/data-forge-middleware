/**
 * Global Errors API Client
 *
 * API functions for global error management.
 */

import { apiClient } from '@/shared/api/client'
import type {
  GlobalErrorPageResponse,
  GlobalErrorResponse,
  GlobalErrorsQueryParams,
  MarkAsReadRequest,
  MarkAsReadResponse,
  UnreadCountResponse,
} from '../model/global-error.types'

const BASE_URL = '/api/v1/account/errors'

/**
 * List global errors with pagination.
 */
export async function listGlobalErrors(
  params: GlobalErrorsQueryParams = {}
): Promise<GlobalErrorPageResponse> {
  const { page = 0, size = 20, unreadOnly = false } = params
  const response = await apiClient.get<GlobalErrorPageResponse>(BASE_URL, {
    params: { page, size, unreadOnly },
  })
  return response.data
}

/**
 * Get unread global errors count.
 */
export async function getUnreadCount(): Promise<UnreadCountResponse> {
  const response = await apiClient.get<UnreadCountResponse>(`${BASE_URL}/unread-count`)
  return response.data
}

/**
 * Get global error details by ID.
 */
export async function getGlobalError(errorId: string): Promise<GlobalErrorResponse> {
  const response = await apiClient.get<GlobalErrorResponse>(`${BASE_URL}/${errorId}`)
  return response.data
}

/**
 * Mark single error as read.
 */
export async function markAsRead(errorId: string): Promise<void> {
  await apiClient.patch(`${BASE_URL}/${errorId}/read`)
}

/**
 * Mark multiple errors as read.
 */
export async function markMultipleAsRead(errorIds: string[]): Promise<MarkAsReadResponse> {
  const request: MarkAsReadRequest = { errorIds }
  const response = await apiClient.post<MarkAsReadResponse>(`${BASE_URL}/mark-as-read`, request)
  return response.data
}

/**
 * Mark all global errors as read.
 */
export async function markAllAsRead(): Promise<MarkAsReadResponse> {
  const response = await apiClient.post<MarkAsReadResponse>(`${BASE_URL}/mark-all-as-read`)
  return response.data
}

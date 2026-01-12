/**
 * Device Authorization API Client (RFC 8628).
 *
 * API client functions for device authorization confirmation flow.
 * Uses Axios instance with Auth0 authentication.
 *
 * @see docs/016-device-authorization-grant.md
 * @version 1.0.0
 */

import { apiClient } from '@/shared/api/client';
import { DEVICE_AUTHORIZATION_CONFIRM } from '@/shared/api/apiRoutes';
import type {
  DeviceCodeInfoResponse,
  ConfirmDeviceRequest,
  ConfirmDeviceResponse,
} from '../model/types';

/**
 * Get device code information for confirmation.
 *
 * @param userCode - The user code entered by the user (e.g., "ABCD-1234")
 * @returns Device code info including status and metadata
 */
export async function getDeviceCodeInfo(userCode: string): Promise<DeviceCodeInfoResponse> {
  const response = await apiClient.get<DeviceCodeInfoResponse>(
    `${DEVICE_AUTHORIZATION_CONFIRM}?user_code=${encodeURIComponent(userCode)}`
  );
  return response.data;
}

/**
 * Confirm device authorization and bind to site.
 *
 * @param request - Confirmation request with user code and selected site
 * @returns Confirmation result with site credentials info
 */
export async function confirmDeviceAuthorization(
  request: ConfirmDeviceRequest
): Promise<ConfirmDeviceResponse> {
  const response = await apiClient.post<ConfirmDeviceResponse>(
    DEVICE_AUTHORIZATION_CONFIRM,
    request
  );
  return response.data;
}

/**
 * Deny device authorization request.
 *
 * @param userCode - The user code to deny
 */
export async function denyDeviceAuthorization(userCode: string): Promise<void> {
  await apiClient.delete(`${DEVICE_AUTHORIZATION_CONFIRM}?user_code=${encodeURIComponent(userCode)}`);
}

export const deviceAuthApi = {
  getDeviceCodeInfo,
  confirmDeviceAuthorization,
  denyDeviceAuthorization,
};

/**
 * Device Authorization API Client (RFC 8628).
 *
 * API client functions for device authorization verification flow.
 * Uses Axios instance with Auth0 authentication.
 *
 * Flow:
 * 1. User receives user_code from device
 * 2. User opens /device-verify?code=XXX
 * 3. Frontend calls getVerifyInfo() → shows siteName/siteDescription
 * 4. User clicks Approve → approveAuthorization() → site created
 * 5. User clicks Deny → denyAuthorization()
 *
 * @see docs/client-integration.md
 * @version 2.0.0
 */

import { apiClient } from '@/shared/api/client';
import { DEVICE_AUTHORIZATION_VERIFY } from '@/shared/api/apiRoutes';
import type {
  DeviceVerifyInfoResponse,
  DeviceVerifyRequest,
  DeviceVerifyResponse,
} from '../model/types';

/**
 * Get authorization info for verification page.
 *
 * @param userCode - The user code (e.g., "ABCD-1234")
 * @returns Authorization info including siteName, siteDescription, expiresAt
 */
export async function getVerifyInfo(userCode: string): Promise<DeviceVerifyInfoResponse> {
  const response = await apiClient.get<DeviceVerifyInfoResponse>(
    `${DEVICE_AUTHORIZATION_VERIFY}?code=${encodeURIComponent(userCode)}`,
    // The verification page renders this call's failures itself, with a message
    // per status (400 already processed, 404 unknown, 410 expired). Letting the
    // global interceptor toast them as well produces two reports of one failure,
    // and for a lookup superseded by a later keystroke the toast is the only
    // one the user sees — over a flow that goes on to succeed (issue #211).
    { suppressErrorToast: true }
  );
  return response.data;
}

/**
 * Approve device authorization.
 * Creates site automatically and returns credentials to device.
 *
 * @param userCode - The user code to approve
 * @returns Verification result with created site info
 */
export async function approveAuthorization(userCode: string): Promise<DeviceVerifyResponse> {
  const request: DeviceVerifyRequest = {
    userCode,
    action: 'approve',
  };
  const response = await apiClient.post<DeviceVerifyResponse>(
    DEVICE_AUTHORIZATION_VERIFY,
    request
  );
  return response.data;
}

/**
 * Deny device authorization request.
 *
 * @param userCode - The user code to deny
 * @returns Verification result (success=true, siteId=null)
 */
export async function denyAuthorization(userCode: string): Promise<DeviceVerifyResponse> {
  const request: DeviceVerifyRequest = {
    userCode,
    action: 'deny',
  };
  const response = await apiClient.post<DeviceVerifyResponse>(
    DEVICE_AUTHORIZATION_VERIFY,
    request
  );
  return response.data;
}

export const deviceAuthApi = {
  getVerifyInfo,
  approveAuthorization,
  denyAuthorization,
};

/**
 * Device Authorization types (RFC 8628 Device Code Flow).
 *
 * @see docs/016-device-authorization-grant.md
 * @version 1.0.0
 */

/**
 * Device code status enum matching backend DeviceCodeStatus.
 */
export type DeviceCodeStatus = 'PENDING' | 'APPROVED' | 'EXPIRED' | 'DENIED';

/**
 * Client metadata attached to device authorization request.
 */
export interface ClientMetadata {
  deviceType?: string;
  deviceName?: string;
  osVersion?: string;
  appVersion?: string;
}

/**
 * Response from GET /api/v1/device/confirm?user_code=xxx
 * Contains device code info for user to confirm/deny.
 */
export interface DeviceCodeInfoResponse {
  userCode: string;
  status: DeviceCodeStatus;
  clientMetadata?: ClientMetadata;
  expiresAt: string;
  createdAt: string;
}

/**
 * Request to confirm device authorization.
 * POST /api/v1/device/confirm
 */
export interface ConfirmDeviceRequest {
  userCode: string;
  siteId: string;
}

/**
 * Response after successful device authorization confirmation.
 */
export interface ConfirmDeviceResponse {
  message: string;
  siteId: string;
  siteDomain: string;
}

/**
 * Site option for dropdown selection during device confirmation.
 */
export interface SiteOption {
  id: string;
  domain: string;
  isActive: boolean;
}

/**
 * Error response for device authorization operations.
 */
export interface DeviceAuthError {
  error: string;
  error_description?: string;
}

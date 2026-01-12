/**
 * Device Authorization types (RFC 8628 Device Code Flow).
 *
 * Adapted for automatic site creation:
 * - Device requests authorization with siteName/siteDescription
 * - User approves → site is created automatically
 * - Device receives credentials via polling
 *
 * @see docs/client-integration.md
 * @version 2.0.0
 */

/**
 * Device authorization status enum matching backend DeviceAuthorizationStatus.
 */
export type DeviceAuthorizationStatus = 'PENDING' | 'APPROVED' | 'DENIED' | 'EXPIRED';

/**
 * Response from GET /api/v1/device/verify?code=XXX
 * Contains authorization info for user to confirm/deny.
 */
export interface DeviceVerifyInfoResponse {
  userCode: string;
  siteName: string;
  siteDescription: string | null;
  expiresAt: string;
}

/**
 * Request to approve/deny device authorization.
 * POST /api/v1/device/verify
 */
export interface DeviceVerifyRequest {
  userCode: string;
  action: 'approve' | 'deny';
}

/**
 * Response after successful device verification.
 */
export interface DeviceVerifyResponse {
  success: boolean;
  siteId: string | null;
  siteName: string | null;
}

/**
 * Error response for device authorization operations.
 * Follows OAuth 2.0 error format.
 */
export interface DeviceAuthError {
  error: string;
  error_description?: string;
  interval?: number;
}

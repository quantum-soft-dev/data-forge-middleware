/**
 * Device Authorization feature module.
 *
 * Provides components and hooks for device authorization verification flow (RFC 8628).
 *
 * @see docs/client-integration.md
 * @version 2.0.0
 */

// API
export { deviceAuthApi } from './api/deviceAuthApi';

// Types
export type {
  DeviceAuthorizationStatus,
  DeviceVerifyInfoResponse,
  DeviceVerifyRequest,
  DeviceVerifyResponse,
  DeviceAuthError,
} from './model/types';

// User code shape
export {
  USER_CODE_CHARS,
  USER_CODE_LENGTH,
  formatUserCode,
  isCompleteUserCode,
} from './model/userCode';

// Failure wording
export { describeVerifyFailure } from './model/verifyError';

// Hooks
export {
  deviceAuthKeys,
  useVerifyInfo,
  useApproveAuthorization,
  useDenyAuthorization,
} from './model/queries';

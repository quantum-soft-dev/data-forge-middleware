/**
 * Device Authorization feature module.
 *
 * Provides components and hooks for device authorization flow (RFC 8628).
 *
 * @see docs/016-device-authorization-grant.md
 * @version 1.0.0
 */

// API
export { deviceAuthApi } from './api/deviceAuthApi';

// Types
export type {
  DeviceCodeStatus,
  ClientMetadata,
  DeviceCodeInfoResponse,
  ConfirmDeviceRequest,
  ConfirmDeviceResponse,
  SiteOption,
  DeviceAuthError,
} from './model/types';

// Hooks
export {
  deviceAuthKeys,
  useDeviceCodeInfo,
  useConfirmDevice,
  useDenyDevice,
} from './model/queries';

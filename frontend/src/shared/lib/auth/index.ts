/**
 * Auth0 authentication utilities.
 *
 * Exports:
 * - AuthenticationGuard: HOC for protecting routes
 * - RoleGuard: Component for role-based access control
 * - UserOnlyGuard: Guard that redirects admins to admin panel
 * - useAuth0Roles: Hook for extracting and checking user roles
 * - currentReturnTo: The location a login redirect must come back to
 * - Session expiry utilities for auto-logout messaging
 *
 * @author Data Forge Team
 * @version 1.1.0
 */
export { AuthenticationGuard } from './AuthenticationGuard';
export { RoleGuard } from './RoleGuard';
export { UserOnlyGuard } from './UserOnlyGuard';
export { useAuth0Roles } from './useAuth0Roles';
export { currentReturnTo, retryReturnTo } from './returnTo';
export {
  setSessionExpired,
  getSessionExpired,
  clearSessionExpired,
  isSessionStorageAvailable,
} from './session-expiry';

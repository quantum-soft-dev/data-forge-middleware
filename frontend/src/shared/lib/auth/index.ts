/**
 * Auth0 authentication utilities.
 *
 * Exports:
 * - AuthenticationGuard: HOC for protecting routes
 * - RoleGuard: Component for role-based access control
 * - UserOnlyGuard: Guard that redirects admins to admin panel
 * - useAuth0Roles: Hook for extracting and checking user roles
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
export { AuthenticationGuard } from './AuthenticationGuard';
export { RoleGuard } from './RoleGuard';
export { UserOnlyGuard } from './UserOnlyGuard';
export { useAuth0Roles } from './useAuth0Roles';

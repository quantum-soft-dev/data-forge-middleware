import { useAuth0 } from '@auth0/auth0-react';

/**
 * Custom hook to extract and check user roles from Auth0 JWT.
 *
 * Roles are stored in a namespaced custom claim in the Auth0 token:
 * - Claim name: https://api.dataforge.com/roles
 * - Format: string[] (e.g., ["ROLE_ADMIN", "ROLE_USER"])
 *
 * Usage:
 * ```tsx
 * function AdminPanel() {
 *   const { roles, hasRole } = useAuth0Roles();
 *
 *   if (!hasRole('ROLE_ADMIN')) {
 *     return <div>Access denied</div>;
 *   }
 *
 *   return <div>Admin content</div>;
 * }
 * ```
 *
 * @returns Object with roles array and hasRole helper function
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
export function useAuth0Roles() {
  const { user } = useAuth0();

  // Extract roles from Auth0 custom claim
  const rolesNamespace = 'https://api.dataforge.com/roles';
  const roles = (user?.[rolesNamespace] as string[]) || [];

  /**
   * Check if user has a specific role.
   *
   * @param role - The role to check (e.g., "ROLE_ADMIN", "ROLE_USER")
   * @returns true if user has the role, false otherwise
   */
  const hasRole = (role: string): boolean => {
    return roles.includes(role);
  };

  /**
   * Check if user has any of the specified roles.
   *
   * @param rolesToCheck - Array of roles to check
   * @returns true if user has at least one of the roles
   */
  const hasAnyRole = (rolesToCheck: string[]): boolean => {
    return rolesToCheck.some((role) => roles.includes(role));
  };

  /**
   * Check if user has all of the specified roles.
   *
   * @param rolesToCheck - Array of roles to check
   * @returns true if user has all of the roles
   */
  const hasAllRoles = (rolesToCheck: string[]): boolean => {
    return rolesToCheck.every((role) => roles.includes(role));
  };

  return {
    roles,
    hasRole,
    hasAnyRole,
    hasAllRoles,
  };
}

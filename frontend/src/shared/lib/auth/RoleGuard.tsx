import { ReactNode } from 'react';
import { useAuth0 } from '@auth0/auth0-react';

/**
 * Role Guard component that restricts access based on user roles.
 *
 * Usage:
 * ```tsx
 * <RoleGuard requiredRole="ROLE_ADMIN">
 *   <AdminPanel />
 * </RoleGuard>
 * ```
 *
 * Behavior:
 * - If user has required role: renders children
 * - If user lacks required role: renders 403 Forbidden message
 * - Extracts roles from Auth0 custom claim: https://api.dataforge.com/roles
 *
 * @param requiredRole - The role required to access the content (e.g., "ROLE_ADMIN", "ROLE_USER")
 * @param children - Content to render if user has required role
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
interface RoleGuardProps {
  requiredRole: string;
  children: ReactNode;
}

export function RoleGuard({ requiredRole, children }: RoleGuardProps) {
  const { user, isLoading } = useAuth0();

  if (isLoading) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-gray-900"></div>
      </div>
    );
  }

  // Extract roles from Auth0 custom claim
  const rolesNamespace = 'https://api.dataforge.com/roles';
  const roles = (user?.[rolesNamespace] as string[]) || [];

  const hasRequiredRole = roles.includes(requiredRole);

  if (!hasRequiredRole) {
    return (
      <div className="flex flex-col items-center justify-center min-h-screen">
        <h1 className="text-4xl font-bold text-gray-900 mb-4">403</h1>
        <h2 className="text-2xl font-semibold text-gray-700 mb-2">Access Denied</h2>
        <p className="text-gray-600">
          You do not have permission to access this page.
        </p>
      </div>
    );
  }

  return <>{children}</>;
}

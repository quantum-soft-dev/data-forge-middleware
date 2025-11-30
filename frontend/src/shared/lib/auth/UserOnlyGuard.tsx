import { ComponentType, useEffect } from 'react';
import { withAuthenticationRequired } from '@auth0/auth0-react';
import { useAuth } from '@/entities/user-session/api/useAuth';

/**
 * Guard that restricts access to regular users only (non-admins).
 * Combines authentication check with admin redirect.
 *
 * Usage:
 * ```tsx
 * <UserOnlyGuard component={SiteManagementPage} />
 * ```
 *
 * Behavior:
 * - If user is not authenticated: redirects to Auth0 login
 * - If user has ROLE_ADMIN: redirects to /admin/users
 * - If user is regular user (no ROLE_ADMIN): renders component
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
interface UserOnlyGuardProps {
  component: ComponentType;
}

function LoadingSpinner() {
  return (
    <div className="flex items-center justify-center min-h-screen">
      <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-gray-900"></div>
    </div>
  );
}

function UserOnlyWrapper({ component: Component }: { component: ComponentType }) {
  const { isLoading, isRolesLoading, hasRole } = useAuth();

  // Wait for both auth and roles to load before checking admin status
  const stillLoading = isLoading || isRolesLoading;

  // Check if user is admin using access token roles
  const isAdmin = hasRole('ROLE_ADMIN');

  useEffect(() => {
    if (!stillLoading && isAdmin) {
      window.location.href = '/admin/users';
    }
  }, [stillLoading, isAdmin]);

  if (stillLoading || isAdmin) {
    return <LoadingSpinner />;
  }

  return <Component />;
}

export function UserOnlyGuard({ component }: UserOnlyGuardProps) {
  const WrappedComponent = withAuthenticationRequired(
    () => <UserOnlyWrapper component={component} />,
    {
      onRedirecting: () => <LoadingSpinner />,
      returnTo: window.location.pathname,
    }
  );

  return <WrappedComponent />;
}

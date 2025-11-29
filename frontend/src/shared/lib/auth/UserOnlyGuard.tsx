import { ComponentType, useEffect } from 'react';
import { useAuth0, withAuthenticationRequired } from '@auth0/auth0-react';
import { env } from '@/shared/config/env';

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
  const { user, isLoading } = useAuth0();

  // Extract roles from Auth0 custom claim
  const rolesClaimKey = `${env.auth0.claimsNamespace}/roles`;
  const roles = (user?.[rolesClaimKey] as string[]) || [];
  const isAdmin = roles.includes('ROLE_ADMIN');

  useEffect(() => {
    if (!isLoading && isAdmin) {
      window.location.href = '/admin/users';
    }
  }, [isLoading, isAdmin]);

  if (isLoading || isAdmin) {
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

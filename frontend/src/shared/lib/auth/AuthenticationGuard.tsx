import { ComponentType } from 'react';
import { withAuthenticationRequired } from '@auth0/auth0-react';

/**
 * Loading spinner component shown during authentication redirect.
 */
function LoadingSpinner() {
  return (
    <div className="flex items-center justify-center min-h-screen">
      <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-gray-900"></div>
    </div>
  );
}

/**
 * Higher-Order Component (HOC) that protects routes requiring authentication.
 *
 * Usage:
 * ```tsx
 * <Route path="/dashboard" element={<AuthenticationGuard component={DashboardPage} />} />
 * ```
 *
 * Behavior:
 * - If user is authenticated: renders the protected component
 * - If user is not authenticated: redirects to Auth0 Universal Login
 * - During redirect: shows loading spinner
 * - After login: navigates back to original route
 *
 * @param component - The component to protect (must be a React component)
 * @returns Protected component wrapped with Auth0 authentication
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
interface AuthenticationGuardProps {
  component: ComponentType;
}

export function AuthenticationGuard({ component }: AuthenticationGuardProps) {
  const Component = withAuthenticationRequired(component, {
    onRedirecting: () => <LoadingSpinner />,
    returnTo: window.location.pathname,
  });

  return <Component />;
}

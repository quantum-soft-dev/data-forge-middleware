import { Auth0Provider as Auth0ProviderSDK } from '@auth0/auth0-react';
import { PropsWithChildren } from 'react';

/**
 * Auth0 Provider wrapper component.
 * Configures Auth0 authentication for the application.
 *
 * Configuration:
 * - domain: Auth0 tenant domain (from env)
 * - clientId: Auth0 SPA application client ID (from env)
 * - audience: Auth0 API identifier (from env)
 * - scope: OpenID Connect scopes
 * - useRefreshTokens: Enable refresh token rotation
 * - cacheLocation: Store tokens in memory (more secure than localStorage)
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
export function Auth0Provider({ children }: PropsWithChildren) {
  const domain = import.meta.env.VITE_AUTH0_DOMAIN;
  const clientId = import.meta.env.VITE_AUTH0_CLIENT_ID;
  const audience = import.meta.env.VITE_AUTH0_AUDIENCE;

  if (!domain || !clientId || !audience) {
    throw new Error(
      'Auth0 configuration missing. Please set VITE_AUTH0_DOMAIN, VITE_AUTH0_CLIENT_ID, and VITE_AUTH0_AUDIENCE environment variables.'
    );
  }

  return (
    <Auth0ProviderSDK
      domain={domain}
      clientId={clientId}
      authorizationParams={{
        redirect_uri: window.location.origin,
        audience: audience,
        scope: 'openid profile email',
      }}
      useRefreshTokens={true}
      cacheLocation="memory"
      onRedirectCallback={(appState) => {
        // After login, navigate back to the page user was on
        window.history.replaceState(
          {},
          document.title,
          appState?.returnTo || window.location.pathname
        );
      }}
    >
      {children}
    </Auth0ProviderSDK>
  );
}

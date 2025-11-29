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
// Runtime environment config (injected by Docker entrypoint)
declare global {
  interface Window {
    _env_?: {
      VITE_AUTH0_DOMAIN?: string;
      VITE_AUTH0_CLIENT_ID?: string;
      VITE_AUTH0_AUDIENCE?: string;
      VITE_AUTH0_CLAIMS_NAMESPACE?: string;
      VITE_API_BASE_URL?: string;
    };
  }
}

export function Auth0Provider({ children }: PropsWithChildren) {
  // Prefer runtime config (window._env_), fallback to build-time (import.meta.env)
  const domain = window._env_?.VITE_AUTH0_DOMAIN || import.meta.env.VITE_AUTH0_DOMAIN;
  const clientId = window._env_?.VITE_AUTH0_CLIENT_ID || import.meta.env.VITE_AUTH0_CLIENT_ID;
  const audience = window._env_?.VITE_AUTH0_AUDIENCE || import.meta.env.VITE_AUTH0_AUDIENCE;

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

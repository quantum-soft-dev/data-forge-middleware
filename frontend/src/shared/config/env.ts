/**
 * Typed environment variable configuration
 *
 * All environment variables must be prefixed with VITE_ to be exposed to the client.
 * See: https://vitejs.dev/guide/env-and-mode.html
 */

export const env = {
  // Auth0 Configuration
  auth0: {
    domain: import.meta.env.VITE_AUTH0_DOMAIN || 'dev-dfm.us.auth0.com',
    clientId: import.meta.env.VITE_AUTH0_CLIENT_ID || '',
    audience: import.meta.env.VITE_AUTH0_AUDIENCE || '',
    // Custom claims namespace - must match Auth0 Post-Login Action
    claimsNamespace: import.meta.env.VITE_AUTH0_CLAIMS_NAMESPACE || 'https://dev.dfm.bitbi.io',
  },

  // API Configuration
  api: {
    baseUrl: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080',
  },

  // Application Configuration
  app: {
    name: 'DataForge Middleware',
    version: '1.0.0',
  },
} as const

// Type-safe environment variable access
export type Env = typeof env

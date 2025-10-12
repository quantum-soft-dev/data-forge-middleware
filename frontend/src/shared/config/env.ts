/**
 * Typed environment variable configuration
 *
 * All environment variables must be prefixed with VITE_ to be exposed to the client.
 * See: https://vitejs.dev/guide/env-and-mode.html
 */

export const env = {
  // Keycloak Configuration
  keycloak: {
    url: import.meta.env.VITE_KEYCLOAK_URL || 'http://localhost:8180',
    realm: import.meta.env.VITE_KEYCLOAK_REALM || 'dataforge',
    clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID || 'dataforge-ui',
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

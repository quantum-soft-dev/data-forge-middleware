/**
 * Typed environment variable configuration
 *
 * Runtime config priority:
 * 1. window._env_ (injected at container startup via env-config.js)
 * 2. import.meta.env (baked at build time)
 * 3. Default fallback values
 *
 * All environment variables must be prefixed with VITE_ to be exposed to the client.
 * See: https://vitejs.dev/guide/env-and-mode.html
 */

/**
 * Get environment variable with runtime config support
 */
function getEnv(key: string, fallback: string): string {
  // In production, prefer runtime config
  if (import.meta.env.PROD && typeof window !== 'undefined') {
    const runtimeValue = (window as { _env_?: Record<string, string> })._env_?.[key]
    if (runtimeValue) return runtimeValue
  }
  // Fall back to build-time or default
  return (import.meta.env[key] as string) || fallback
}

export const env = {
  // Auth0 Configuration
  auth0: {
    domain: getEnv('VITE_AUTH0_DOMAIN', 'dev-dfm.us.auth0.com'),
    clientId: getEnv('VITE_AUTH0_CLIENT_ID', ''),
    audience: getEnv('VITE_AUTH0_AUDIENCE', ''),
    // Custom claims namespace - must match Auth0 Post-Login Action
    claimsNamespace: getEnv('VITE_AUTH0_CLAIMS_NAMESPACE', 'https://dev.dfm.bitbi.io'),
  },

  // API Configuration
  api: {
    baseUrl: getEnv('VITE_API_BASE_URL', 'http://localhost:8080'),
  },

  // Application Configuration
  app: {
    name: 'DataForge Middleware',
    version: '1.0.0',
  },
} as const

// Type-safe environment variable access
export type Env = typeof env

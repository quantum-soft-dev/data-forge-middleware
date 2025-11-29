import axios from 'axios'

// Window._env_ type is declared in src/app/providers/Auth0Provider.tsx

/**
 * Get API base URL with runtime config support
 *
 * Priority:
 * 1. Runtime config (window._env_.VITE_API_BASE_URL) - set at container startup
 * 2. Build-time config (import.meta.env.VITE_API_BASE_URL) - baked during build
 * 3. Default fallback ('/api')
 */
function getApiBaseUrl(): string {
  if (import.meta.env.PROD) {
    return window._env_?.VITE_API_BASE_URL || import.meta.env.VITE_API_BASE_URL || '/api'
  }
  return '/api' // Use Vite proxy in development
}

/**
 * Axios instance configured for DataForge backend API
 *
 * Base URL: Uses Vite proxy configuration (/api => http://localhost:8080/api)
 * In production: Uses runtime config from window._env_ (injected at container startup)
 * Timeout: 30 seconds
 *
 * Note: JWT authentication is added via interceptors in interceptors.ts
 */
export const apiClient = axios.create({
  baseURL: getApiBaseUrl(),
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
})

export default apiClient

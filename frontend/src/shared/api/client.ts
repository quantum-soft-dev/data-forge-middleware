import axios from 'axios'

/**
 * Axios instance configured for DataForge backend API
 *
 * Base URL: Uses Vite proxy configuration (/api => http://localhost:8080/api)
 * In production: Set VITE_API_BASE_URL to actual API endpoint
 * Timeout: 30 seconds
 *
 * Note: JWT authentication is added via interceptors in interceptors.ts
 */
export const apiClient = axios.create({
  baseURL: import.meta.env.PROD
    ? import.meta.env.VITE_API_BASE_URL
    : '/api', // Use Vite proxy in development
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
})

export default apiClient

/**
 * Debug logger utility with environment checks.
 *
 * Logs messages only in development mode when DEBUG_AUTH environment variable is enabled.
 * Prevents sensitive token data from leaking in production console logs.
 *
 * Usage:
 * - logger.debug('[Auth]', 'Message') - only logs in dev with DEBUG_AUTH=true
 * - logger.info('[Info]', 'Message') - logs in dev mode
 * - logger.warn('[Warn]', 'Message') - logs in all environments
 * - logger.error('[Error]', error) - logs in all environments
 */

const isDevelopment = process.env.NODE_ENV === 'development'
const isDebugAuth = process.env.REACT_APP_DEBUG_AUTH === 'true'

export const logger = {
  /**
   * Debug logging for sensitive auth operations.
   * Only logs in development with REACT_APP_DEBUG_AUTH=true.
   */
  debug: (prefix: string, ...args: unknown[]) => {
    if (isDevelopment && isDebugAuth) {
      console.log(prefix, ...args)
    }
  },

  /**
   * Info logging for general operations.
   * Only logs in development mode.
   */
  info: (prefix: string, ...args: unknown[]) => {
    if (isDevelopment) {
      console.log(prefix, ...args)
    }
  },

  /**
   * Warning logging.
   * Logs in all environments.
   */
  warn: (prefix: string, ...args: unknown[]) => {
    console.warn(prefix, ...args)
  },

  /**
   * Error logging.
   * Logs in all environments.
   */
  error: (prefix: string, ...args: unknown[]) => {
    console.error(prefix, ...args)
  },
}

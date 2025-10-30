/**
 * Token Refresh Handler
 *
 * NOTE: Token refresh is handled automatically by react-oidc-context.
 * This component exists for documentation purposes and future customization.
 *
 * react-oidc-context configuration (in AuthProvider.tsx):
 * - automaticSilentRenew: true
 * - Silent refresh occurs via hidden iframe before token expiration
 * - No user interruption (per FR-019)
 *
 * See research.md for token refresh flow details.
 */
export function TokenRefreshHandler() {
  // Token refresh is automatic via react-oidc-context
  // No UI or logic needed here
  return null
}

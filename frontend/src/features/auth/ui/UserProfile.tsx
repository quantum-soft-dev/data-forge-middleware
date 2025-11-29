import { useAuth0 } from '@auth0/auth0-react'
import { User, Mail, Shield } from 'lucide-react'
import { env } from '@/shared/config/env'

/**
 * UserProfile component displays authenticated user information from Auth0
 *
 * Features:
 * - Displays user name, email, and profile picture
 * - Shows assigned roles from custom Auth0 claims
 * - Handles missing profile data gracefully
 * - Returns null if user is not authenticated
 *
 * Custom Claims:
 * - Roles are extracted from configurable namespace (VITE_AUTH0_CLAIMS_NAMESPACE)
 * - Roles follow ROLE_* prefix convention (e.g., ROLE_ADMIN, ROLE_USER)
 *
 * @author Data Forge Team
 * @version 1.0.0 (Auth0 migration)
 */
export function UserProfile() {
  const { user, isAuthenticated, isLoading } = useAuth0()

  if (isLoading) {
    return (
      <div className="flex items-center justify-center p-4">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-gray-900"></div>
      </div>
    )
  }

  if (!isAuthenticated || !user) {
    return null
  }

  // Extract roles from custom Auth0 claim (namespace from env)
  const rolesClaimKey = `${env.auth0.claimsNamespace}/roles`
  const roles = (user[rolesClaimKey] as string[]) || []

  return (
    <div className="rounded-lg border bg-card p-6 shadow-sm">
      <div className="flex flex-col space-y-4">
        {/* Profile Picture and Name */}
        <div className="flex items-center space-x-4">
          {user.picture ? (
            <img
              src={user.picture}
              alt={user.name || 'User avatar'}
              className="h-16 w-16 rounded-full border-2 border-gray-200"
            />
          ) : (
            <div className="flex h-16 w-16 items-center justify-center rounded-full bg-gray-200">
              <User className="h-8 w-8 text-gray-500" />
            </div>
          )}
          <div>
            <h3 className="text-lg font-semibold">{user.name || 'Unknown User'}</h3>
            <p className="text-sm text-muted-foreground">Auth0 Account</p>
          </div>
        </div>

        {/* Email */}
        <div className="flex items-center space-x-2 text-sm">
          <Mail className="h-4 w-4 text-muted-foreground" />
          <span className="text-muted-foreground">{user.email || 'No email provided'}</span>
        </div>

        {/* Roles */}
        {roles.length > 0 && (
          <div className="space-y-2">
            <div className="flex items-center space-x-2 text-sm font-medium">
              <Shield className="h-4 w-4 text-muted-foreground" />
              <span>Roles:</span>
            </div>
            <div className="flex flex-wrap gap-2">
              {roles.map((role) => (
                <span
                  key={role}
                  className="inline-flex items-center rounded-full bg-blue-100 px-3 py-1 text-xs font-medium text-blue-800"
                >
                  {role.replace('ROLE_', '')}
                </span>
              ))}
            </div>
          </div>
        )}

        {/* User ID (for debugging) */}
        <div className="border-t pt-4">
          <p className="text-xs text-muted-foreground">
            User ID: <span className="font-mono">{user.sub}</span>
          </p>
        </div>
      </div>
    </div>
  )
}

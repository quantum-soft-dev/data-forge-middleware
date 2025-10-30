/**
 * AccountStatusBadge Component
 *
 * Per T030: Display account status indicators.
 * Shows isActive, keycloakEnabled, and passwordTemporary states with color-coded badges.
 *
 * Features:
 * - Active/Inactive status (green/red)
 * - Keycloak enabled status (green/red)
 * - Password temporary indicator (yellow/gray)
 * - Accessibility with ARIA labels
 */

interface AccountStatusBadgeProps {
  isActive: boolean
  keycloakEnabled?: boolean
  passwordTemporary?: boolean
  className?: string
}

export function AccountStatusBadge({
  isActive,
  keycloakEnabled,
  passwordTemporary,
  className = '',
}: AccountStatusBadgeProps) {
  return (
    <div className={`flex flex-wrap gap-2 ${className}`}>
      {/* Account Active Status */}
      <span
        className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${
          isActive
            ? 'bg-green-100 text-green-800'
            : 'bg-red-100 text-red-800'
        }`}
        aria-label={`Account status: ${isActive ? 'Active' : 'Inactive'}`}
      >
        {isActive ? '● Active' : '● Inactive'}
      </span>

      {/* Keycloak Integration Status */}
      {keycloakEnabled !== undefined && (
        <span
          className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${
            keycloakEnabled
              ? 'bg-green-100 text-green-800'
              : 'bg-red-100 text-red-800'
          }`}
          aria-label={`Keycloak status: ${keycloakEnabled ? 'Enabled' : 'Disabled'}`}
        >
          {keycloakEnabled ? 'Keycloak Enabled' : 'Keycloak Disabled'}
        </span>
      )}

      {/* Password Temporary Status */}
      {passwordTemporary !== undefined && (
        <span
          className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${
            passwordTemporary
              ? 'bg-yellow-100 text-yellow-800'
              : 'bg-gray-100 text-gray-800'
          }`}
          aria-label={`Password type: ${passwordTemporary ? 'Temporary' : 'Permanent'}`}
        >
          {passwordTemporary ? '⚠ Temporary Password' : 'Permanent Password'}
        </span>
      )}
    </div>
  )
}

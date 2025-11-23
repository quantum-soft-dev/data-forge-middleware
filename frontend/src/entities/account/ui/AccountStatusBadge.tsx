/**
 * AccountStatusBadge Component
 *
 * Per T030: Display account status indicators.
 * Shows isActive and isBlocked states with color-coded badges.
 *
 * Features:
 * - Active/Inactive status (green/red)
 * - Auth0 blocked status (red/green)
 * - Accessibility with ARIA labels
 */

interface AccountStatusBadgeProps {
  isActive: boolean
  isBlocked?: boolean
  className?: string
}

export function AccountStatusBadge({
  isActive,
  isBlocked,
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

      {/* Auth0 Blocked Status */}
      {isBlocked !== undefined && (
        <span
          className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${
            isBlocked
              ? 'bg-red-100 text-red-800'
              : 'bg-green-100 text-green-800'
          }`}
          aria-label={`Auth0 status: ${isBlocked ? 'Blocked' : 'Active'}`}
        >
          {isBlocked ? '● Blocked' : '● Auth0 Active'}
        </span>
      )}
    </div>
  )
}

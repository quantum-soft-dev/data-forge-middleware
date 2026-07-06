/**
 * AccountStatusBadge Component
 *
 * Per T030: Display account status indicators.
 * Shows isActive and isBlocked states as monitoring pills (024, T032).
 *
 * Features:
 * - Active/Inactive status (success/critical)
 * - Auth0 blocked status (critical/success)
 * - Accessibility with ARIA labels
 */

import { Badge } from '@/shared/ui/ui/badge'

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
      <Badge
        variant={isActive ? 'success' : 'critical'}
        dot
        aria-label={`Account status: ${isActive ? 'Active' : 'Inactive'}`}
      >
        {isActive ? 'Active' : 'Inactive'}
      </Badge>

      {/* Auth0 Blocked Status */}
      {isBlocked !== undefined && (
        <Badge
          variant={isBlocked ? 'critical' : 'success'}
          dot
          aria-label={`Auth0 status: ${isBlocked ? 'Blocked' : 'Active'}`}
        >
          {isBlocked ? 'Blocked' : 'Auth0 Active'}
        </Badge>
      )}
    </div>
  )
}

/**
 * PluginStatusBadge Component
 *
 * Displays plugin enabled/disabled status as a monitoring pill (024, T031).
 */

import { Badge } from '@/shared/ui/ui/badge'

interface PluginStatusBadgeProps {
  isEnabled: boolean
  className?: string
}

export function PluginStatusBadge({ isEnabled, className = '' }: PluginStatusBadgeProps) {
  return (
    <Badge
      variant={isEnabled ? 'success' : 'neutral'}
      dot
      className={className}
      aria-label={`Plugin status: ${isEnabled ? 'Enabled' : 'Disabled'}`}
    >
      {isEnabled ? 'Enabled' : 'Disabled'}
    </Badge>
  )
}

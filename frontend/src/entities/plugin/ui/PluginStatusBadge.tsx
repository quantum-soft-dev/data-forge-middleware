/**
 * PluginStatusBadge Component
 *
 * Displays plugin enabled/disabled status with color-coded badges.
 */

interface PluginStatusBadgeProps {
  isEnabled: boolean
  className?: string
}

export function PluginStatusBadge({ isEnabled, className = '' }: PluginStatusBadgeProps) {
  return (
    <span
      className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${
        isEnabled
          ? 'bg-green-100 text-green-800'
          : 'bg-gray-100 text-gray-800'
      } ${className}`}
      aria-label={`Plugin status: ${isEnabled ? 'Enabled' : 'Disabled'}`}
    >
      {isEnabled ? 'Enabled' : 'Disabled'}
    </span>
  )
}

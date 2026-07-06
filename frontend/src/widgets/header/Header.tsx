/**
 * Header Widget
 *
 * App header with navigation menu and logout button.
 * Displayed on all protected pages (Dashboard, Accounts).
 *
 * Per FR-008: Navigation menu with links to Dashboard and Accounts pages.
 * Role-based access: User Management visible only for ADMIN role.
 * Monitoring visual language (024, T013): hairline bottom border,
 * ink/ink-secondary links, brand active accent.
 */

import { Link } from '@tanstack/react-router'
import { User } from 'lucide-react'
import { LogoutButton } from '@/features/auth/logout/LogoutButton'
import { useAuth } from '@/entities/user-session/api/useAuth'
import { Badge } from '@/shared/ui/ui/badge'

const NAV_LINK_CLASSES =
  'text-sm font-medium text-ink-secondary hover:text-ink transition-colors'
const NAV_LINK_ACTIVE = {
  className: 'text-sm font-medium text-brand hover:text-brand',
}

export function Header() {
  const { user, hasRole, isRolesLoading } = useAuth()
  const isAdmin = !isRolesLoading && hasRole('ROLE_ADMIN')

  // Get user name from token (prefer name, fallback to email)
  const userName = user?.name || user?.email || 'User'

  return (
    <header className="border-b border-separator bg-white">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div className="flex h-16 items-center justify-between">
          {/* Logo / Title */}
          <Link to="/dashboard" className="flex items-center gap-3 hover:opacity-90 transition-opacity">
            <img
              src="/favicon.svg"
              alt="DataForge Logo"
              className="h-8 w-8"
            />
            <h1 className="text-base font-medium tracking-[-0.24px] text-ink">
              DataForge Middleware
            </h1>
          </Link>

          {/* Navigation Menu */}
          <nav className="flex items-center space-x-6">
            <Link to="/dashboard" className={NAV_LINK_CLASSES} activeProps={NAV_LINK_ACTIVE}>
              Dashboard
            </Link>

            {/* Site Management - only visible for regular users (not admins) */}
            {!isAdmin && (
              <Link to="/account/sites" className={NAV_LINK_CLASSES} activeProps={NAV_LINK_ACTIVE}>
                Site Management
              </Link>
            )}

            {/* Upload History - only visible for regular users (not admins) */}
            {!isAdmin && (
              <Link
                to="/account/upload-history"
                className={NAV_LINK_CLASSES}
                activeProps={NAV_LINK_ACTIVE}
              >
                Upload History
              </Link>
            )}

            {/* Plugins - only visible for regular users (not admins) */}
            {!isAdmin && (
              <Link to="/account/plugins" className={NAV_LINK_CLASSES} activeProps={NAV_LINK_ACTIVE}>
                Plugins
              </Link>
            )}

            {/* User Management - only visible for ADMIN role */}
            {isAdmin && (
              <Link to="/admin/users" className={NAV_LINK_CLASSES} activeProps={NAV_LINK_ACTIVE}>
                User Management
              </Link>
            )}

            {/* Plugins - only visible for ADMIN role */}
            {isAdmin && (
              <Link to="/admin/plugins" className={NAV_LINK_CLASSES} activeProps={NAV_LINK_ACTIVE}>
                Plugins
              </Link>
            )}

            {/* Settings - only visible for ADMIN role */}
            {isAdmin && (
              <Link to="/admin/settings" className={NAV_LINK_CLASSES} activeProps={NAV_LINK_ACTIVE}>
                Settings
              </Link>
            )}

            {/* Current User Info */}
            <div className="ml-4 flex items-center gap-2 border-l border-separator pl-4 text-sm text-ink-secondary">
              <User className="h-4 w-4" strokeWidth={1.5} />
              <span className="font-medium">{userName}</span>
              {isAdmin && (
                <Badge variant="info" className="ml-1 px-2 py-0.5">
                  Admin
                </Badge>
              )}
            </div>

            {/* Logout Button */}
            <LogoutButton />
          </nav>
        </div>
      </div>
    </header>
  )
}

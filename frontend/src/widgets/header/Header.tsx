/**
 * Header Widget
 *
 * App header with navigation menu and logout button.
 * Displayed on all protected pages (Dashboard, Accounts).
 *
 * Per FR-008: Navigation menu with links to Dashboard and Accounts pages.
 * Role-based access: User Management visible only for ADMIN role.
 * Monitoring visual language (024, T013 rev.2): floating rounded card per
 * design_handoff_delta_sync prototype — white r10 + panel shadow inside a
 * 1120px container; nav links are 14px/400 hover-pills, active = brand on
 * brand-50; user shown as initials avatar.
 */

import { Link } from '@tanstack/react-router'
import { LogoutButton } from '@/features/auth/logout/LogoutButton'
import { useAuth } from '@/entities/user-session/api/useAuth'
import { Badge } from '@/shared/ui/ui/badge'
import { monitoringTokens } from '@/shared/ui/tokens'

// Active state via aria-current (set by TanStack Router) — the attribute
// selector outranks the base color utilities regardless of CSS order.
const NAV_LINK_CLASSES =
  'rounded-lg px-3 py-1.5 text-sm text-ink-secondary transition-colors ' +
  'hover:bg-surface-subtle hover:text-ink ' +
  'aria-[current=page]:bg-brand-50 aria-[current=page]:text-brand'

/** "Boris Pliss" → "BP"; falls back to the first two characters. */
function initialsOf(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase()
  return name.slice(0, 2).toUpperCase()
}

export function Header() {
  const { user, hasRole, isRolesLoading } = useAuth()
  const isAdmin = !isRolesLoading && hasRole('ROLE_ADMIN')

  // Get user name from token (prefer name, fallback to email)
  const userName = user?.name || user?.email || 'User'

  return (
    <header className="mx-auto mt-4 max-w-[1120px] px-6">
      <div className="flex items-center justify-between rounded-[10px] bg-white px-4 py-2.5 shadow-panel">
        {/* Logo / Title */}
        <Link to="/dashboard" className="flex items-center gap-2.5 hover:opacity-90 transition-opacity">
          <img
            src="/favicon.svg"
            alt="DataForge Logo"
            className="h-7 w-7"
          />
          <h1 className="text-[17px] font-semibold tracking-[-0.24px] text-ink">
            DataForge Middleware
          </h1>
        </Link>

        {/* Navigation Menu */}
        <nav className="flex items-center gap-0.5">
          <Link to="/dashboard" className={NAV_LINK_CLASSES}>
            Dashboard
          </Link>

          {/* Site Management - only visible for regular users (not admins) */}
          {!isAdmin && (
            <Link to="/account/sites" className={NAV_LINK_CLASSES}>
              Site Management
            </Link>
          )}

          {/* Upload History - only visible for regular users (not admins) */}
          {!isAdmin && (
            <Link
              to="/account/upload-history"
              className={NAV_LINK_CLASSES}
             
            >
              Upload History
            </Link>
          )}

          {/* Plugins - only visible for regular users (not admins) */}
          {!isAdmin && (
            <Link to="/account/plugins" className={NAV_LINK_CLASSES}>
              Plugins
            </Link>
          )}

          {/* User Management - only visible for ADMIN role */}
          {isAdmin && (
            <Link to="/admin/users" className={NAV_LINK_CLASSES}>
              User Management
            </Link>
          )}

          {/* Plugins - only visible for ADMIN role */}
          {isAdmin && (
            <Link to="/admin/plugins" className={NAV_LINK_CLASSES}>
              Plugins
            </Link>
          )}

          {/* Settings - only visible for ADMIN role */}
          {isAdmin && (
            <Link to="/admin/settings" className={NAV_LINK_CLASSES}>
              Settings
            </Link>
          )}

          {/* Current User */}
          <div className="ml-3 flex items-center gap-2">
            {isAdmin && (
              <Badge variant="info" className="px-2 py-0.5">
                Admin
              </Badge>
            )}
            <span
              title={userName}
              aria-label={`Signed in as ${userName}`}
              className="inline-flex h-8 w-8 items-center justify-center rounded-full text-[13px] font-medium"
              style={{ background: monitoringTokens.blue50, color: monitoringTokens.primary }}
            >
              {initialsOf(userName)}
            </span>
            <LogoutButton />
          </div>
        </nav>
      </div>
    </header>
  )
}

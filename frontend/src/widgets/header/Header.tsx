/**
 * Header Widget
 *
 * App header with navigation menu and logout button.
 * Displayed on all protected pages (Dashboard, Accounts).
 *
 * Per FR-008: Navigation menu with links to Dashboard and Accounts pages.
 * Role-based access: User Management visible only for ADMIN role.
 */

import { Link } from '@tanstack/react-router'
import { User } from 'lucide-react'
import { LogoutButton } from '@/features/auth/logout/LogoutButton'
import { useAuth } from '@/entities/user-session/api/useAuth'

export function Header() {
  const { user, hasRole } = useAuth()
  // Keycloak uses ROLE_ prefix for realm roles
  const isAdmin = hasRole('ROLE_ADMIN')

  // Get user name from token (prefer name, fallback to email, then username)
  const userName = user?.profile?.name || user?.profile?.email || user?.profile?.preferred_username || 'User'

  return (
    <header className="border-b border-gray-200 bg-white shadow-sm">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div className="flex h-16 items-center justify-between">
          {/* Logo / Title */}
          <div className="flex items-center">
            <h1 className="text-xl font-bold text-gray-900">
              DataForge Middleware
            </h1>
          </div>

          {/* Navigation Menu */}
          <nav className="flex items-center space-x-6">
            <Link
              to="/dashboard"
              className="text-sm font-medium text-gray-700 hover:text-gray-900 transition-colors"
              activeProps={{
                className: 'text-sm font-medium text-primary hover:text-primary',
              }}
            >
              Dashboard
            </Link>

            {/* User Management - only visible for ADMIN role */}
            {isAdmin && (
              <Link
                to="/admin/users"
                className="text-sm font-medium text-gray-700 hover:text-gray-900 transition-colors"
                activeProps={{
                  className: 'text-sm font-medium text-primary hover:text-primary',
                }}
              >
                User Management
              </Link>
            )}

            {/* Current User Info */}
            <div className="flex items-center gap-2 ml-4 border-l border-gray-300 pl-4 text-sm text-gray-600">
              <User className="h-4 w-4" />
              <span className="font-medium">{userName}</span>
              {isAdmin && (
                <span className="ml-1 rounded-full bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-800">
                  Admin
                </span>
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

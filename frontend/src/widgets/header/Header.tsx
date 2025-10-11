/**
 * Header Widget
 *
 * App header with navigation menu and logout button.
 * Displayed on all protected pages (Dashboard, Subscribers).
 *
 * Per FR-008: Navigation menu with links to Dashboard and Subscribers pages.
 */

import { Link } from '@tanstack/react-router'
import { LogoutButton } from '@/features/auth/logout/LogoutButton'

export function Header() {
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
            <Link
              to="/subscribers"
              className="text-sm font-medium text-gray-700 hover:text-gray-900 transition-colors"
              activeProps={{
                className: 'text-sm font-medium text-primary hover:text-primary',
              }}
            >
              Subscribers
            </Link>
            <div className="ml-4 border-l border-gray-300 pl-4">
              <LogoutButton />
            </div>
          </nav>
        </div>
      </div>
    </header>
  )
}

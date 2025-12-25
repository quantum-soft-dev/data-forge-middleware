/**
 * MyPluginsPage - User plugin management page.
 *
 * Features:
 * - View available plugins for activation
 * - View user's activated plugins
 * - Activate/Deactivate plugins
 *
 * Route: /account/plugins
 *
 * Feature: 013-plugin-system
 */

import { Header } from '@/widgets/header/Header'
import { MyPluginsWidget } from '@/widgets/my-plugins'
import { WidgetErrorBoundary } from '@/shared/ui/WidgetErrorBoundary'

export function MyPluginsPage() {
  return (
    <div className="min-h-screen bg-gray-50">
      <Header />

      <main className="container mx-auto py-8 max-w-4xl px-4 sm:px-6 lg:px-8">
        {/* Page header */}
        <div className="mb-8">
          <h1 className="text-3xl font-bold text-gray-900">My Plugins</h1>
          <p className="mt-2 text-gray-600">
            Manage your plugin integrations and activate new features
          </p>
        </div>

        {/* Plugins widget */}
        <WidgetErrorBoundary widgetName="My Plugins">
          <MyPluginsWidget />
        </WidgetErrorBoundary>
      </main>
    </div>
  )
}

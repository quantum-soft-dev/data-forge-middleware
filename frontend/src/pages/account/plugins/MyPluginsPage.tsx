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
import { PageHeader } from '@/shared/ui/page-header'

export function MyPluginsPage() {
  return (
    <div className="min-h-screen bg-white">
      <Header />

      <main className="container mx-auto py-8 max-w-4xl px-4 sm:px-6 lg:px-8">
        <PageHeader
          title="My Plugins"
          subtitle="Manage your plugin integrations and activate new features"
        />

        {/* Plugins widget */}
        <WidgetErrorBoundary widgetName="My Plugins">
          <MyPluginsWidget />
        </WidgetErrorBoundary>
      </main>
    </div>
  )
}

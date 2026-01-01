/**
 * PluginHistoryPage - Plugin SQL Generation History
 *
 * Admin page for viewing and managing SQL generation history for a specific
 * account's plugin. Allows viewing SQL content, downloading files, regenerating,
 * and clearing all history.
 *
 * Route: /admin/plugins/:pluginId/accounts/:accountId/history
 * Requires: ROLE_ADMIN
 */

import { useParams, Link } from '@tanstack/react-router'
import { ArrowLeft, Database, History } from 'lucide-react'
import { Header } from '@/widgets/header/Header'
import { Button } from '@/shared/ui/ui/button'
import { PluginHistoryWidget } from '@/widgets/plugin-history/PluginHistoryWidget'

export default function PluginHistoryPage() {
  const { pluginId, accountId } = useParams({
    from: '/admin/plugins/$pluginId/accounts/$accountId/history',
  })

  return (
    <div className="min-h-screen bg-gray-50">
      <Header />

      <main className="mx-auto max-w-7xl px-4 py-8 sm:px-6 lg:px-8">
        {/* Back navigation */}
        <div className="mb-6">
          <Link to="/admin/plugins">
            <Button variant="ghost" size="sm">
              <ArrowLeft className="h-4 w-4 mr-2" />
              Back to Plugins
            </Button>
          </Link>
        </div>

        {/* Page header */}
        <div className="mb-8">
          <div className="flex items-center gap-3 mb-2">
            <History className="h-8 w-8 text-gray-600" />
            <h1 className="text-3xl font-bold text-gray-900">
              SQL Generation History
            </h1>
          </div>
          <div className="flex items-center gap-4 text-sm text-gray-600">
            <div className="flex items-center gap-1">
              <Database className="h-4 w-4" />
              <span>Plugin: {pluginId}</span>
            </div>
            <span>|</span>
            <span className="font-mono text-xs">{accountId}</span>
          </div>
        </div>

        {/* History widget */}
        <section className="bg-white rounded-lg shadow-sm border p-6">
          <PluginHistoryWidget pluginId={pluginId} accountId={accountId} />
        </section>
      </main>
    </div>
  )
}

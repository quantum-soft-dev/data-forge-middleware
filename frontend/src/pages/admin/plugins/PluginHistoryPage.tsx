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
import { ArrowLeft, Database } from 'lucide-react'
import { Header } from '@/widgets/header/Header'
import { PageHeader } from '@/shared/ui/page-header'
import { Button } from '@/shared/ui/ui/button'
import { PluginHistoryWidget } from '@/widgets/plugin-history/PluginHistoryWidget'

export default function PluginHistoryPage() {
  const { pluginId, accountId } = useParams({
    from: '/admin/plugins/$pluginId/accounts/$accountId/history',
  })

  return (
    <div className="min-h-screen bg-white">
      <Header />

      <main className="mx-auto max-w-[1120px] px-6 py-6">
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
          <PageHeader title="SQL Generation History" />
          <div className="mt-1.5 flex items-center gap-4 text-sm text-ink-secondary">
            <div className="flex items-center gap-1">
              <Database className="h-4 w-4" />
              <span>Plugin: {pluginId}</span>
            </div>
            <span>|</span>
            <span className="font-mono text-xs">{accountId}</span>
          </div>
        </div>

        {/* History widget */}
        <section className="rounded-lg bg-white p-6 shadow-panel">
          <PluginHistoryWidget pluginId={pluginId} accountId={accountId} />
        </section>
      </main>
    </div>
  )
}

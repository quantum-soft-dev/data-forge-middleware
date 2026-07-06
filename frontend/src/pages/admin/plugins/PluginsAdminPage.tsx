/**
 * PluginsAdminPage - Plugin Administration
 *
 * Admin page for viewing registered plugins, audit logs, and SQL history.
 * Read-only: displays plugin status, capabilities, and audit trail.
 *
 * Route: /admin/plugins
 * Requires: ROLE_ADMIN
 */

import { useState } from 'react'
import { Plug, FileText, Database } from 'lucide-react'
import { Header } from '@/widgets/header/Header'
import { PageHeader } from '@/shared/ui/page-header'
import { Badge } from '@/shared/ui/ui/badge'
import { Tabs, TabsList, TabsTrigger } from '@/shared/ui/ui/tabs'
import { PluginListWidget } from '@/widgets/plugin-admin/PluginListWidget'
import { AuditLogWidget } from '@/widgets/plugin-admin/AuditLogWidget'
import { SqlHistoryWidget } from '@/widgets/plugin-admin/SqlHistoryWidget'

type TabType = 'plugins' | 'audit' | 'history'

export default function PluginsAdminPage() {
  const [activeTab, setActiveTab] = useState<TabType>('plugins')
  const [selectedPluginId, setSelectedPluginId] = useState<string | undefined>()

  const handlePluginClick = (pluginId: string) => {
    setSelectedPluginId(pluginId)
    setActiveTab('audit')
  }

  const handlePluginHistoryClick = (pluginId: string) => {
    setSelectedPluginId(pluginId)
    setActiveTab('history')
  }

  return (
    <div className="min-h-screen bg-surface-hover">
      <Header />

      <main className="mx-auto max-w-7xl px-4 py-8 sm:px-6 lg:px-8">
        <PageHeader
          title="Plugin Administration"
          subtitle="View registered plugins and their audit logs"
        />

        {/* Tabs */}
        <Tabs
          value={activeTab}
          onValueChange={(value) => {
            const tab = value as TabType
            setActiveTab(tab)
            if (tab === 'plugins') setSelectedPluginId(undefined)
          }}
          className="mb-6"
        >
          <TabsList aria-label="Tabs">
            <TabsTrigger value="plugins" className="gap-2">
              <Plug className="h-4 w-4" strokeWidth={1.5} />
              Registered Plugins
            </TabsTrigger>
            <TabsTrigger value="audit" className="gap-2">
              <FileText className="h-4 w-4" strokeWidth={1.5} />
              Audit Logs
              {selectedPluginId && activeTab === 'audit' && (
                <Badge variant="info" className="ml-1 px-2 py-0.5">
                  {selectedPluginId}
                </Badge>
              )}
            </TabsTrigger>
            <TabsTrigger value="history" className="gap-2">
              <Database className="h-4 w-4" strokeWidth={1.5} />
              SQL History
              {selectedPluginId && activeTab === 'history' && (
                <Badge variant="info" className="ml-1 px-2 py-0.5">
                  {selectedPluginId}
                </Badge>
              )}
            </TabsTrigger>
          </TabsList>
        </Tabs>

        {/* Tab content */}
        {activeTab === 'plugins' && (
          <section>
            <PluginListWidget
              onPluginClick={handlePluginClick}
              onHistoryClick={handlePluginHistoryClick}
            />
          </section>
        )}

        {activeTab === 'audit' && (
          <section>
            <AuditLogWidget initialPluginId={selectedPluginId} />
          </section>
        )}

        {activeTab === 'history' && (
          <section>
            <SqlHistoryWidget pluginId={selectedPluginId} />
          </section>
        )}
      </main>
    </div>
  )
}

/**
 * PluginsAdminPage - Plugin Administration
 *
 * Admin page for viewing registered plugins and audit logs.
 * Read-only: displays plugin status, capabilities, and audit trail.
 *
 * Route: /admin/plugins
 * Requires: ROLE_ADMIN
 */

import { useState } from 'react'
import { Plug, FileText } from 'lucide-react'
import { Header } from '@/widgets/header/Header'
import { PluginListWidget } from '@/widgets/plugin-admin/PluginListWidget'
import { AuditLogWidget } from '@/widgets/plugin-admin/AuditLogWidget'

type TabType = 'plugins' | 'audit'

export default function PluginsAdminPage() {
  const [activeTab, setActiveTab] = useState<TabType>('plugins')
  const [selectedPluginId, setSelectedPluginId] = useState<string | undefined>()

  const handlePluginClick = (pluginId: string) => {
    setSelectedPluginId(pluginId)
    setActiveTab('audit')
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <Header />

      <main className="mx-auto max-w-7xl px-4 py-8 sm:px-6 lg:px-8">
        {/* Page header */}
        <div className="mb-8">
          <h1 className="text-3xl font-bold text-gray-900">Plugin Administration</h1>
          <p className="mt-2 text-sm text-gray-600">
            View registered plugins and their audit logs
          </p>
        </div>

        {/* Tabs */}
        <div className="mb-6 border-b border-gray-200">
          <nav className="-mb-px flex space-x-8" aria-label="Tabs">
            <button
              onClick={() => {
                setActiveTab('plugins')
                setSelectedPluginId(undefined)
              }}
              className={`flex items-center gap-2 whitespace-nowrap border-b-2 px-1 py-4 text-sm font-medium ${
                activeTab === 'plugins'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
              }`}
            >
              <Plug className="h-4 w-4" />
              Registered Plugins
            </button>
            <button
              onClick={() => setActiveTab('audit')}
              className={`flex items-center gap-2 whitespace-nowrap border-b-2 px-1 py-4 text-sm font-medium ${
                activeTab === 'audit'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
              }`}
            >
              <FileText className="h-4 w-4" />
              Audit Logs
              {selectedPluginId && (
                <span className="ml-1 rounded-full bg-blue-100 px-2 py-0.5 text-xs text-blue-800">
                  {selectedPluginId}
                </span>
              )}
            </button>
          </nav>
        </div>

        {/* Tab content */}
        {activeTab === 'plugins' && (
          <section>
            <PluginListWidget onPluginClick={handlePluginClick} />
          </section>
        )}

        {activeTab === 'audit' && (
          <section>
            <AuditLogWidget initialPluginId={selectedPluginId} />
          </section>
        )}
      </main>
    </div>
  )
}

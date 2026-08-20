/**
 * PluginHistoryWidget
 *
 * Container component for the plugin history management feature.
 * Composes the generation list, SQL viewer, and action dialogs.
 *
 * @module widgets/plugin-history/PluginHistoryWidget
 */

import { useState, useCallback } from 'react'
import { Button } from '@/shared/ui/ui/button'
import { Trash2 } from 'lucide-react'
import {
  GenerationListTable,
  SqlContentViewer,
  ClearHistoryDialog,
  useGenerationsQuery,
  useDownloadSqlFile,
} from '@/features/plugin-history'
import type { SqlGenerationSummary } from '@/features/plugin-history'

interface PluginHistoryWidgetProps {
  pluginId: string
  accountId: string
}

export function PluginHistoryWidget({
  pluginId,
  accountId,
}: PluginHistoryWidgetProps) {
  // Pagination
  const [page, setPage] = useState(0)

  // Modal state
  const [viewingGeneration, setViewingGeneration] =
    useState<SqlGenerationSummary | null>(null)
  const [isClearDialogOpen, setIsClearDialogOpen] = useState(false)

  // Data fetching
  const {
    data,
    isLoading,
    isError,
    error,
    refetch,
  } = useGenerationsQuery({
    pluginId,
    accountId,
    page,
    size: 20,
    // The superseded model died with the retired regeneration path (#190): no row was ever
    // superseded and nothing writes the flag any more, so there is nothing to include. The
    // server keeps accepting the parameter (recorded decision on #190).
    includeSuperseded: false,
  })

  const downloadMutation = useDownloadSqlFile()

  // Handlers
  const handleViewContent = useCallback((generation: SqlGenerationSummary) => {
    setViewingGeneration(generation)
  }, [])

  const handleDownload = useCallback(
    (generation: SqlGenerationSummary) => {
      downloadMutation.mutate({
        pluginId,
        accountId,
        generationId: generation.id,
      })
    },
    [downloadMutation, pluginId, accountId]
  )

  const handlePageChange = useCallback((newPage: number) => {
    setPage(newPage)
  }, [])

  const handleCleared = useCallback(() => {
    refetch()
  }, [refetch])

  if (isError) {
    return (
      <div className="rounded-lg border border-red-200 bg-red-50 p-6">
        <h3 className="text-lg font-medium text-red-800 mb-2">
          Failed to load history
        </h3>
        <p className="text-sm text-red-700">
          {error instanceof Error ? error.message : 'An unexpected error occurred'}
        </p>
        <Button
          variant="outline"
          size="sm"
          className="mt-4"
          onClick={() => refetch()}
        >
          Retry
        </Button>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      {/* Header with actions */}
      <div className="flex items-center justify-end">
        <Button
          variant="destructive"
          size="sm"
          onClick={() => setIsClearDialogOpen(true)}
        >
          <Trash2 className="h-4 w-4 mr-2" />
          Clear All History
        </Button>
      </div>

      {/* Generation list table */}
      <GenerationListTable
        data={data}
        isLoading={isLoading}
        onViewContent={handleViewContent}
        onDownload={handleDownload}
        onPageChange={handlePageChange}
      />

      {/* SQL Content Viewer Modal */}
      <SqlContentViewer
        pluginId={pluginId}
        accountId={accountId}
        generation={viewingGeneration}
        isOpen={viewingGeneration !== null}
        onClose={() => setViewingGeneration(null)}
      />

      {/* Clear History Dialog */}
      <ClearHistoryDialog
        pluginId={pluginId}
        accountId={accountId}
        isOpen={isClearDialogOpen}
        onClose={() => setIsClearDialogOpen(false)}
        onCleared={handleCleared}
      />
    </div>
  )
}

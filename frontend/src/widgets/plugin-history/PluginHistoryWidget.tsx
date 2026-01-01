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
import { Checkbox } from '@/shared/ui/ui/checkbox'
import { Trash2 } from 'lucide-react'
import {
  GenerationListTable,
  SqlContentViewer,
  ClearHistoryDialog,
  RegenerateDialog,
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
  // Pagination and filters
  const [page, setPage] = useState(0)
  const [includeSuperseded, setIncludeSuperseded] = useState(false)

  // Modal state
  const [viewingGeneration, setViewingGeneration] =
    useState<SqlGenerationSummary | null>(null)
  const [regeneratingGeneration, setRegeneratingGeneration] =
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
    includeSuperseded,
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

  const handleRegenerate = useCallback((generation: SqlGenerationSummary) => {
    setRegeneratingGeneration(generation)
  }, [])

  const handlePageChange = useCallback((newPage: number) => {
    setPage(newPage)
  }, [])

  const handleCleared = useCallback(() => {
    refetch()
  }, [refetch])

  const handleRegenerated = useCallback(() => {
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
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-4">
          <div className="flex items-center space-x-2">
            <Checkbox
              id="include-superseded"
              checked={includeSuperseded}
              onCheckedChange={(checked) => {
                setIncludeSuperseded(checked === true)
                setPage(0)
              }}
            />
            <label
              htmlFor="include-superseded"
              className="text-sm font-medium leading-none peer-disabled:cursor-not-allowed peer-disabled:opacity-70"
            >
              Show superseded generations
            </label>
          </div>
        </div>

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
        onRegenerate={handleRegenerate}
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

      {/* Regenerate Dialog */}
      <RegenerateDialog
        pluginId={pluginId}
        accountId={accountId}
        generation={regeneratingGeneration}
        isOpen={regeneratingGeneration !== null}
        onClose={() => setRegeneratingGeneration(null)}
        onRegenerated={handleRegenerated}
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

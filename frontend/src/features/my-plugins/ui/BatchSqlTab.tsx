/**
 * BatchSqlTab Component
 *
 * Main container for batch SQL management in My Plugins widget.
 * Shows batches with SQL status and provides actions to generate, view and delete SQL.
 * (Regeneration was retired by #190; re-creating a batch's SQL is Delete + Generate.)
 *
 * Features:
 * - Site filtering
 * - Configurable page size
 * - Pagination
 */

import { useState, useCallback } from 'react'
import { AlertCircle, ChevronLeft, ChevronRight } from 'lucide-react'
import { toast } from 'sonner'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/shared/ui/ui/alert-dialog'
import { Button } from '@/shared/ui/ui/button'
import { BatchSqlTable } from './BatchSqlTable'
import { SqlContentViewerDialog } from './SqlContentViewerDialog'
import { BatchPropertiesDialog } from './BatchPropertiesDialog'
import { PluginTabFilters, type SqlFilterState } from './PluginTabFilters'
import {
  useBatchSqlStatusQuery,
  useGenerateSqlMutation,
  useDeleteGenerationMutation,
} from '../api/batchSqlQueries'
import type { BatchSqlStatus } from '../model/types'

interface BatchSqlTabProps {
  pluginId: string
}

type DialogAction = 'generate' | 'delete' | null

export function BatchSqlTab({ pluginId }: BatchSqlTabProps) {
  // Filter and pagination state
  const [page, setPage] = useState(0)
  const [filters, setFilters] = useState<SqlFilterState>({
    size: 20,
  })

  // Dialog state
  const [selectedBatch, setSelectedBatch] = useState<BatchSqlStatus | null>(null)
  const [dialogAction, setDialogAction] = useState<DialogAction>(null)
  const [viewerOpen, setViewerOpen] = useState(false)
  const [propertiesOpen, setPropertiesOpen] = useState(false)
  const [pendingBatchId, setPendingBatchId] = useState<string | null>(null)

  // Queries and mutations
  const { data, isLoading, isError, error } = useBatchSqlStatusQuery({
    pluginId,
    page,
    size: filters.size,
    siteId: filters.siteId,
  })
  const generateMutation = useGenerateSqlMutation()
  const deleteMutation = useDeleteGenerationMutation()

  // Filter and pagination handlers
  const handleFiltersChange = useCallback((newFilters: SqlFilterState) => {
    setFilters(newFilters)
    setPage(0) // Reset to first page when filters change
  }, [])

  const handlePrevPage = useCallback(() => {
    setPage((p) => Math.max(0, p - 1))
  }, [])

  const handleNextPage = useCallback(() => {
    if (data && page < data.totalPages - 1) {
      setPage((p) => p + 1)
    }
  }, [data, page])

  // Handlers for opening dialogs
  const handleViewSql = useCallback((batch: BatchSqlStatus) => {
    setSelectedBatch(batch)
    setViewerOpen(true)
  }, [])

  const handleGenerateClick = useCallback((batch: BatchSqlStatus) => {
    setSelectedBatch(batch)
    setDialogAction('generate')
  }, [])

  const handleDeleteClick = useCallback((batch: BatchSqlStatus) => {
    setSelectedBatch(batch)
    setDialogAction('delete')
  }, [])

  const handleCloseDialog = useCallback(() => {
    setDialogAction(null)
    setSelectedBatch(null)
  }, [])

  const handleCloseViewer = useCallback(() => {
    setViewerOpen(false)
    setSelectedBatch(null)
  }, [])

  const handleShowProperties = useCallback((batch: BatchSqlStatus) => {
    setSelectedBatch(batch)
    setPropertiesOpen(true)
  }, [])

  const handleCloseProperties = useCallback(() => {
    setPropertiesOpen(false)
    setSelectedBatch(null)
  }, [])

  // Action handlers
  const handleConfirmGenerate = useCallback(() => {
    if (!selectedBatch) return

    setPendingBatchId(selectedBatch.batchId)
    generateMutation.mutate(
      { pluginId, request: { batchId: selectedBatch.batchId } },
      {
        onSuccess: (result) => {
          if (result.generated) {
            toast.success('SQL Generated', {
              description: `Generated ${result.statementCount} statements (${result.insertCount} INSERT, ${result.updateCount} UPDATE, ${result.deleteCount} DELETE)`,
            })
          } else {
            toast.info('Generation Skipped', {
              description: result.message || 'No SQL was generated for this batch',
            })
          }
          handleCloseDialog()
        },
        onError: (err) => {
          toast.error('Generation Failed', {
            description: err.message || 'Failed to generate SQL',
          })
        },
        onSettled: () => {
          setPendingBatchId(null)
        },
      }
    )
  }, [selectedBatch, pluginId, generateMutation, handleCloseDialog])

  const handleConfirmDelete = useCallback(() => {
    if (!selectedBatch?.generationId) return

    setPendingBatchId(selectedBatch.batchId)
    deleteMutation.mutate(
      { pluginId, generationId: selectedBatch.generationId },
      {
        onSuccess: () => {
          toast.success('SQL Deleted', {
            description: 'SQL generation has been deleted',
          })
          handleCloseDialog()
        },
        onError: (err) => {
          toast.error('Deletion Failed', {
            description: err.message || 'Failed to delete SQL generation',
          })
        },
        onSettled: () => {
          setPendingBatchId(null)
        },
      }
    )
  }, [selectedBatch, pluginId, deleteMutation, handleCloseDialog])

  return (
    <>
      <PluginTabFilters
        filters={filters}
        onFiltersChange={handleFiltersChange}
        showDateRange={false}
      />

      {isError ? (
        <div className="rounded-lg border border-danger-border bg-danger-bg p-4 text-center">
          <AlertCircle className="mx-auto h-8 w-8 text-danger-solid" />
          <p className="mt-2 text-sm text-danger-text">
            Failed to load batches: {error?.message || 'Unknown error'}
          </p>
        </div>
      ) : (
        <>
          <BatchSqlTable
            batches={data?.content ?? []}
            isLoading={isLoading}
            onViewSql={handleViewSql}
            onGenerateSql={handleGenerateClick}
            onDeleteSql={handleDeleteClick}
            onShowProperties={handleShowProperties}
            pendingBatchId={pendingBatchId}
            hasFilters={!!filters.siteId}
          />

          {/* Pagination */}
          {data && data.totalPages > 1 && (
            <div className="flex items-center justify-between border-t border-separator pt-4 mt-4">
              <div className="text-sm text-ink-secondary">
                Page {data.page + 1} of {data.totalPages} ({data.totalElements} total)
              </div>
              <div className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={handlePrevPage}
                  disabled={page === 0}
                >
                  <ChevronLeft className="h-4 w-4" />
                  Previous
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={handleNextPage}
                  disabled={page >= data.totalPages - 1}
                >
                  Next
                  <ChevronRight className="h-4 w-4" />
                </Button>
              </div>
            </div>
          )}
        </>
      )}

      {/* SQL Content Viewer */}
      <SqlContentViewerDialog
        pluginId={pluginId}
        batch={selectedBatch}
        isOpen={viewerOpen}
        onClose={handleCloseViewer}
      />

      {/* Batch Properties Dialog */}
      <BatchPropertiesDialog
        batch={selectedBatch}
        isOpen={propertiesOpen}
        onClose={handleCloseProperties}
      />

      {/* Generate Confirmation Dialog */}
      <AlertDialog open={dialogAction === 'generate'} onOpenChange={(open) => !open && handleCloseDialog()}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Generate SQL</AlertDialogTitle>
            <AlertDialogDescription>
              Generate SQL statements for batch from <strong>{selectedBatch?.siteDomain}</strong>?
              <br /><br />
              This will compare the batch data with the baseline and create INSERT, UPDATE, and DELETE statements.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={generateMutation.isPending}>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleConfirmGenerate}
              disabled={generateMutation.isPending}
            >
              {generateMutation.isPending ? 'Generating...' : 'Generate'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* Delete Confirmation Dialog */}
      <AlertDialog open={dialogAction === 'delete'} onOpenChange={(open) => !open && handleCloseDialog()}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete SQL</AlertDialogTitle>
            <AlertDialogDescription>
              Delete SQL generation for batch from <strong>{selectedBatch?.siteDomain}</strong>?
              <br /><br />
              This will permanently delete the SQL file. Generate can re-create it only while the
              batch is above the plugin&apos;s current baselines — after a reinitialize, older
              batches generate nothing. Re-created SQL gets a new timestamp, so a Bit BI client
              whose cursor already passed this batch will receive its SQL a second time. If the
              client has already fetched this batch, or Generate produces nothing, use
              Reinitialize instead.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deleteMutation.isPending}>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleConfirmDelete}
              disabled={deleteMutation.isPending}
              className="bg-danger-solid hover:bg-danger-solid-hover"
            >
              {deleteMutation.isPending ? 'Deleting...' : 'Delete'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}

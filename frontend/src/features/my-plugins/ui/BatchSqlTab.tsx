/**
 * BatchSqlTab Component
 *
 * Main container for batch SQL management in My Plugins widget.
 * Shows batches with SQL status and provides actions to generate,
 * view, regenerate, and delete SQL.
 */

import { useState, useCallback } from 'react'
import { AlertCircle } from 'lucide-react'
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
import { BatchSqlTable } from './BatchSqlTable'
import { SqlContentViewerDialog } from './SqlContentViewerDialog'
import {
  useBatchSqlStatusQuery,
  useGenerateSqlMutation,
  useRegenerateSqlMutation,
  useDeleteGenerationMutation,
} from '../api/batchSqlQueries'
import type { BatchSqlStatus } from '../model/types'

interface BatchSqlTabProps {
  pluginId: string
}

type DialogAction = 'generate' | 'regenerate' | 'delete' | null

export function BatchSqlTab({ pluginId }: BatchSqlTabProps) {
  // State
  const [selectedBatch, setSelectedBatch] = useState<BatchSqlStatus | null>(null)
  const [dialogAction, setDialogAction] = useState<DialogAction>(null)
  const [viewerOpen, setViewerOpen] = useState(false)
  const [pendingBatchId, setPendingBatchId] = useState<string | null>(null)

  // Queries and mutations
  const { data, isLoading, isError, error } = useBatchSqlStatusQuery(pluginId)
  const generateMutation = useGenerateSqlMutation()
  const regenerateMutation = useRegenerateSqlMutation()
  const deleteMutation = useDeleteGenerationMutation()

  // Handlers for opening dialogs
  const handleViewSql = useCallback((batch: BatchSqlStatus) => {
    setSelectedBatch(batch)
    setViewerOpen(true)
  }, [])

  const handleGenerateClick = useCallback((batch: BatchSqlStatus) => {
    setSelectedBatch(batch)
    setDialogAction('generate')
  }, [])

  const handleRegenerateClick = useCallback((batch: BatchSqlStatus) => {
    setSelectedBatch(batch)
    setDialogAction('regenerate')
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

  // Action handlers
  const handleConfirmGenerate = useCallback(() => {
    if (!selectedBatch) return

    setPendingBatchId(selectedBatch.batchId)
    generateMutation.mutate(
      { pluginId, request: { batchId: selectedBatch.batchId } },
      {
        onSuccess: (result) => {
          toast.success('SQL Generated', {
            description: `Generated ${result.statementCount} statements (${result.insertCount} INSERT, ${result.updateCount} UPDATE, ${result.deleteCount} DELETE)`,
          })
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

  const handleConfirmRegenerate = useCallback(() => {
    if (!selectedBatch?.generationId) return

    setPendingBatchId(selectedBatch.batchId)
    regenerateMutation.mutate(
      { pluginId, generationId: selectedBatch.generationId },
      {
        onSuccess: (result) => {
          toast.success('SQL Regenerated', {
            description: `Regenerated ${result.statementCount} statements`,
          })
          handleCloseDialog()
        },
        onError: (err) => {
          toast.error('Regeneration Failed', {
            description: err.message || 'Failed to regenerate SQL',
          })
        },
        onSettled: () => {
          setPendingBatchId(null)
        },
      }
    )
  }, [selectedBatch, pluginId, regenerateMutation, handleCloseDialog])

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

  // Error state
  if (isError) {
    return (
      <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-center">
        <AlertCircle className="mx-auto h-8 w-8 text-red-400" />
        <p className="mt-2 text-sm text-red-600">
          Failed to load batches: {error?.message || 'Unknown error'}
        </p>
      </div>
    )
  }

  return (
    <>
      <BatchSqlTable
        batches={data?.content ?? []}
        isLoading={isLoading}
        onViewSql={handleViewSql}
        onGenerateSql={handleGenerateClick}
        onRegenerateSql={handleRegenerateClick}
        onDeleteSql={handleDeleteClick}
        pendingBatchId={pendingBatchId}
      />

      {/* SQL Content Viewer */}
      <SqlContentViewerDialog
        pluginId={pluginId}
        batch={selectedBatch}
        isOpen={viewerOpen}
        onClose={handleCloseViewer}
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

      {/* Regenerate Confirmation Dialog */}
      <AlertDialog open={dialogAction === 'regenerate'} onOpenChange={(open) => !open && handleCloseDialog()}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Regenerate SQL</AlertDialogTitle>
            <AlertDialogDescription>
              Regenerate SQL statements for batch from <strong>{selectedBatch?.siteDomain}</strong>?
              <br /><br />
              This will delete the existing SQL and generate new statements. Use this if the baseline has changed or if you need fresh SQL.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={regenerateMutation.isPending}>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleConfirmRegenerate}
              disabled={regenerateMutation.isPending}
            >
              {regenerateMutation.isPending ? 'Regenerating...' : 'Regenerate'}
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
              This will permanently delete the SQL file. You can regenerate it later if needed.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deleteMutation.isPending}>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleConfirmDelete}
              disabled={deleteMutation.isPending}
              className="bg-red-600 hover:bg-red-700"
            >
              {deleteMutation.isPending ? 'Deleting...' : 'Delete'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}

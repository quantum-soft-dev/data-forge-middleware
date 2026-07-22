/**
 * Dialog component for confirming history clear operation.
 *
 * @module features/plugin-history/ui/ClearHistoryDialog
 */

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
import { Badge } from '@/shared/ui/ui/badge'
import { Skeleton } from '@/shared/ui/ui/skeleton'
import { AlertTriangle, Trash2 } from 'lucide-react'
import { useHistorySummaryQuery, useClearHistoryMutation } from '../api/plugin-history.queries'

interface ClearHistoryDialogProps {
  pluginId: string
  accountId: string
  isOpen: boolean
  onClose: () => void
  onCleared: () => void
}

function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${sizes[i]}`
}

export function ClearHistoryDialog({
  pluginId,
  accountId,
  isOpen,
  onClose,
  onCleared,
}: ClearHistoryDialogProps) {
  const { data: summary, isLoading } = useHistorySummaryQuery(
    pluginId,
    accountId,
    isOpen
  )

  const clearMutation = useClearHistoryMutation()

  const handleClear = () => {
    clearMutation.mutate(
      { pluginId, accountId },
      {
        onSuccess: () => {
          onCleared()
          onClose()
        },
      }
    )
  }

  return (
    <AlertDialog open={isOpen} onOpenChange={(open) => !open && onClose()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle className="flex items-center gap-2 text-destructive">
            <AlertTriangle className="h-5 w-5" />
            Clear All Plugin History
          </AlertDialogTitle>
          <AlertDialogDescription>
            This action cannot be undone. This will permanently delete all SQL
            generation records and files.
          </AlertDialogDescription>
        </AlertDialogHeader>

        {isLoading ? (
          <div className="space-y-2 py-4">
            <Skeleton className="h-4 w-full" />
            <Skeleton className="h-4 w-3/4" />
          </div>
        ) : summary ? (
          <div className="py-4 space-y-4">
            <div className="bg-muted p-4 rounded-lg space-y-2">
              <div className="flex justify-between">
                <span className="text-sm text-muted-foreground">
                  SQL Generations to delete:
                </span>
                <span className="font-medium">
                  {summary.generationCount.toLocaleString()}
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-sm text-muted-foreground">
                  Total file size:
                </span>
                <span className="font-medium">
                  {formatBytes(summary.totalFileSizeBytes)}
                </span>
              </div>
              <div className="flex justify-between items-center">
                <span className="text-sm text-muted-foreground">
                  Plugin status after clear:
                </span>
                <Badge variant="neutral">Deactivated</Badge>
              </div>
            </div>

            {summary.hasActiveBatches && (
              <div className="flex items-start gap-2 p-3 bg-yellow-50 dark:bg-yellow-900/20 rounded-lg border border-yellow-200 dark:border-yellow-800">
                <AlertTriangle className="h-4 w-4 text-yellow-600 dark:text-yellow-400 mt-0.5" />
                <div className="text-sm text-yellow-700 dark:text-yellow-300">
                  <strong>Warning:</strong> There are active batches in progress.
                  Clearing history may cause data inconsistencies.
                </div>
              </div>
            )}
          </div>
        ) : null}

        <AlertDialogFooter>
          <AlertDialogCancel disabled={clearMutation.isPending}>
            Cancel
          </AlertDialogCancel>
          <AlertDialogAction
            onClick={handleClear}
            disabled={clearMutation.isPending || isLoading}
            className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
          >
            {clearMutation.isPending ? (
              'Clearing...'
            ) : (
              <>
                <Trash2 className="h-4 w-4 mr-2" />
                Clear All History
              </>
            )}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

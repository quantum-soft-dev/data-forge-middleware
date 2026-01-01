/**
 * Dialog component for confirming SQL regeneration.
 *
 * @module features/plugin-history/ui/RegenerateDialog
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
import { RefreshCw } from 'lucide-react'
import { useRegenerateSqlMutation } from '../api/plugin-history.queries'
import type { SqlGenerationSummary } from '../model/types'

interface RegenerateDialogProps {
  pluginId: string
  accountId: string
  generation: SqlGenerationSummary | null
  isOpen: boolean
  onClose: () => void
  onRegenerated: () => void
}

function formatDate(dateString: string): string {
  const date = new Date(dateString)
  return new Intl.DateTimeFormat('en-US', {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

export function RegenerateDialog({
  pluginId,
  accountId,
  generation,
  isOpen,
  onClose,
  onRegenerated,
}: RegenerateDialogProps) {
  const regenerateMutation = useRegenerateSqlMutation()

  const handleRegenerate = () => {
    if (!generation) return

    regenerateMutation.mutate(
      { pluginId, accountId, generationId: generation.id },
      {
        onSuccess: () => {
          onRegenerated()
          onClose()
        },
      }
    )
  }

  if (!generation) return null

  return (
    <AlertDialog open={isOpen} onOpenChange={(open) => !open && onClose()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle className="flex items-center gap-2">
            <RefreshCw className="h-5 w-5" />
            Regenerate SQL
          </AlertDialogTitle>
          <AlertDialogDescription>
            This will regenerate the SQL file using the original batch data.
            The current generation will be marked as superseded.
          </AlertDialogDescription>
        </AlertDialogHeader>

        <div className="py-4 space-y-4">
          <div className="bg-muted p-4 rounded-lg space-y-2">
            <div className="flex justify-between">
              <span className="text-sm text-muted-foreground">Site:</span>
              <span className="font-medium">{generation.siteDomain}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-sm text-muted-foreground">
                Original created:
              </span>
              <span className="font-medium">
                {formatDate(generation.createdAt)}
              </span>
            </div>
            <div className="flex justify-between">
              <span className="text-sm text-muted-foreground">Statements:</span>
              <span className="font-medium">
                {generation.statementCount.toLocaleString()}
              </span>
            </div>
            <div className="flex justify-between items-center">
              <span className="text-sm text-muted-foreground">Type:</span>
              <Badge variant={generation.isInitialLoad ? 'default' : 'secondary'}>
                {generation.isInitialLoad ? 'Initial Load' : 'Comparison'}
              </Badge>
            </div>
          </div>

          <p className="text-sm text-muted-foreground">
            After regeneration, the original file will be marked as superseded
            and a new generation will be created with updated SQL.
          </p>
        </div>

        <AlertDialogFooter>
          <AlertDialogCancel disabled={regenerateMutation.isPending}>
            Cancel
          </AlertDialogCancel>
          <AlertDialogAction
            onClick={handleRegenerate}
            disabled={regenerateMutation.isPending}
          >
            {regenerateMutation.isPending ? (
              'Regenerating...'
            ) : (
              <>
                <RefreshCw className="h-4 w-4 mr-2" />
                Regenerate
              </>
            )}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

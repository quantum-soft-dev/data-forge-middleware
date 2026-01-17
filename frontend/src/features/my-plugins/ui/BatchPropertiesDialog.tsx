/**
 * BatchPropertiesDialog Component
 *
 * Modal dialog for viewing detailed batch and SQL generation properties.
 * Shows all batch information and SQL generation statistics.
 */

import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from '@/shared/ui/ui/dialog'
import { Button } from '@/shared/ui/ui/button'
import type { BatchSqlStatus } from '../model/types'

interface BatchPropertiesDialogProps {
  batch: BatchSqlStatus | null
  isOpen: boolean
  onClose: () => void
}

/**
 * Format timestamp for display in YYYY-MM-DD HH:MM:SS format.
 */
function formatTimestamp(isoString: string | null): string {
  if (!isoString) return '-'
  return isoString.slice(0, 19).replace('T', ' ')
}

/**
 * Format file size for display.
 */
function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/**
 * Property row component for consistent styling.
 */
function PropertyRow({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex justify-between py-1.5 border-b border-gray-100 last:border-0">
      <span className="text-sm text-gray-500">{label}</span>
      <span className={`text-sm ${mono ? 'font-mono text-xs' : ''}`}>{value}</span>
    </div>
  )
}

export function BatchPropertiesDialog({
  batch,
  isOpen,
  onClose,
}: BatchPropertiesDialogProps) {
  if (!batch) return null

  const handleOpenChange = (open: boolean) => {
    if (!open) {
      onClose()
    }
  }

  // Determine SQL status text
  let sqlStatus = 'Not Generated'
  if (batch.isBaseline) {
    sqlStatus = 'Baseline (No SQL Required)'
  } else if (batch.hasSql) {
    sqlStatus = 'Generated'
  }

  return (
    <Dialog open={isOpen} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>Batch Properties</DialogTitle>
          <DialogDescription>
            Detailed information about the batch and SQL generation.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          {/* Batch Information Section */}
          <div>
            <h4 className="text-sm font-semibold text-gray-700 mb-2 uppercase tracking-wide">
              Batch Information
            </h4>
            <div className="bg-gray-50 rounded-lg p-3">
              <PropertyRow label="Batch ID" value={batch.batchId} mono />
              <PropertyRow label="Site" value={batch.siteDomain} />
              <PropertyRow label="Site ID" value={batch.siteId} mono />
              <PropertyRow label="Status" value={batch.status} />
              <PropertyRow label="Completed" value={formatTimestamp(batch.completedAt)} />
              <PropertyRow label="Files" value={String(batch.fileCount)} />
              <PropertyRow label="Total Size" value={formatFileSize(batch.totalSizeBytes)} />
            </div>
          </div>

          {/* SQL Generation Section */}
          <div>
            <h4 className="text-sm font-semibold text-gray-700 mb-2 uppercase tracking-wide">
              SQL Generation
            </h4>
            <div className="bg-gray-50 rounded-lg p-3">
              <PropertyRow label="Status" value={sqlStatus} />

              {batch.generationId && (
                <PropertyRow label="Generation ID" value={batch.generationId} mono />
              )}

              {batch.generatedAt && (
                <PropertyRow label="Generated At" value={formatTimestamp(batch.generatedAt)} />
              )}

              {batch.hasSql && batch.insertCount != null && (
                <>
                  <PropertyRow label="Inserts" value={String(batch.insertCount)} />
                  <PropertyRow label="Updates" value={String(batch.updateCount ?? 0)} />
                  <PropertyRow label="Deletes" value={String(batch.deleteCount ?? 0)} />
                  <PropertyRow
                    label="Total Statements"
                    value={String((batch.insertCount ?? 0) + (batch.updateCount ?? 0) + (batch.deleteCount ?? 0))}
                  />
                </>
              )}

              {batch.isBaseline && (
                <div className="mt-2 text-xs text-gray-500 italic">
                  This is the baseline batch. SQL is not generated for baseline batches as they serve as the reference point for detecting changes.
                </div>
              )}

              {!batch.hasSql && !batch.isBaseline && (
                <div className="mt-2 text-xs text-gray-500 italic">
                  SQL has not been generated for this batch yet. Use the Generate button to create SQL statements.
                </div>
              )}
            </div>
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            Close
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

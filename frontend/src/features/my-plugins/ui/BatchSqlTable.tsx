/**
 * BatchSqlTable Component
 *
 * Table displaying batches with their SQL generation status.
 * Allows generating, viewing, regenerating, and deleting SQL for batches.
 */

import { Database, Eye, RefreshCw, Trash2, Plus, Loader2, Info } from 'lucide-react'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/shared/ui/ui/table'
import { Badge } from '@/shared/ui/ui/badge'
import { Button } from '@/shared/ui/ui/button'
import type { BatchSqlStatus } from '../model/types'

interface BatchSqlTableProps {
  batches: BatchSqlStatus[]
  isLoading: boolean
  onViewSql: (batch: BatchSqlStatus) => void
  onGenerateSql: (batch: BatchSqlStatus) => void
  onRegenerateSql: (batch: BatchSqlStatus) => void
  onDeleteSql: (batch: BatchSqlStatus) => void
  onShowProperties: (batch: BatchSqlStatus) => void
  pendingBatchId?: string | null
  /** True when filters are applied (for empty state message) */
  hasFilters?: boolean
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
 * Get result cell based on batch SQL status.
 * Shows generation result: Baseline, SQL Generated with stats, Pending, or Failed.
 */
function ResultCell({ batch }: { batch: BatchSqlStatus }) {
  // Baseline batch - no SQL needed
  if (batch.isBaseline) {
    return (
      <Badge variant="secondary" className="bg-gray-100 text-gray-600">
        Baseline
      </Badge>
    )
  }

  // SQL generated successfully - show stats
  if (batch.hasSql && batch.generationId) {
    const stats: string[] = []
    if (batch.insertCount != null && batch.insertCount > 0) {
      stats.push(`+${batch.insertCount} ins`)
    }
    if (batch.updateCount != null && batch.updateCount > 0) {
      stats.push(`~${batch.updateCount} upd`)
    }
    if (batch.deleteCount != null && batch.deleteCount > 0) {
      stats.push(`-${batch.deleteCount} del`)
    }

    return (
      <div className="flex flex-col gap-0.5">
        <Badge variant="default" className="bg-green-100 text-green-700 hover:bg-green-100 w-fit">
          SQL Generated
        </Badge>
        {stats.length > 0 && (
          <span className="text-xs text-gray-500">
            {stats.join(', ')}
          </span>
        )}
      </div>
    )
  }

  // No SQL yet - pending
  return (
    <Badge variant="outline" className="border-amber-300 text-amber-700">
      Pending
    </Badge>
  )
}

/**
 * Action buttons for a batch row.
 */
function BatchActions({
  batch,
  onViewSql,
  onGenerateSql,
  onRegenerateSql,
  onDeleteSql,
  onShowProperties,
  isPending,
}: {
  batch: BatchSqlStatus
  onViewSql: (batch: BatchSqlStatus) => void
  onGenerateSql: (batch: BatchSqlStatus) => void
  onRegenerateSql: (batch: BatchSqlStatus) => void
  onDeleteSql: (batch: BatchSqlStatus) => void
  onShowProperties: (batch: BatchSqlStatus) => void
  isPending: boolean
}) {
  // Baseline batch - only show info icon
  if (batch.isBaseline) {
    return (
      <div className="flex items-center gap-1">
        <Button
          variant="ghost"
          size="sm"
          onClick={() => onShowProperties(batch)}
          className="h-8 w-8 p-0"
          title="View batch properties"
        >
          <Info className="h-4 w-4" />
        </Button>
        <span
          className="text-xs text-gray-400 cursor-help"
          title="Baseline batch - no SQL needed. It serves as the reference point for changes."
        >
          Baseline
        </span>
      </div>
    )
  }

  // Show loading spinner if pending
  if (isPending) {
    return (
      <div className="flex items-center gap-2">
        <Loader2 className="h-4 w-4 animate-spin text-gray-400" />
        <span className="text-xs text-gray-500">Processing...</span>
      </div>
    )
  }

  // Batch has SQL - show view, regenerate, delete, properties
  if (batch.hasSql && batch.generationId) {
    return (
      <div className="flex items-center gap-1">
        <Button
          variant="ghost"
          size="sm"
          onClick={() => onViewSql(batch)}
          className="h-8 w-8 p-0"
          title="View SQL"
        >
          <Eye className="h-4 w-4" />
        </Button>

        <Button
          variant="ghost"
          size="sm"
          onClick={() => onRegenerateSql(batch)}
          className="h-8 w-8 p-0"
          title="Regenerate SQL"
        >
          <RefreshCw className="h-4 w-4" />
        </Button>

        <Button
          variant="ghost"
          size="sm"
          onClick={() => onDeleteSql(batch)}
          className="h-8 w-8 p-0 text-red-500 hover:text-red-600"
          title="Delete SQL"
        >
          <Trash2 className="h-4 w-4" />
        </Button>

        <Button
          variant="ghost"
          size="sm"
          onClick={() => onShowProperties(batch)}
          className="h-8 w-8 p-0"
          title="View batch properties"
        >
          <Info className="h-4 w-4" />
        </Button>
      </div>
    )
  }

  // Batch without SQL - show generate button and properties
  return (
    <div className="flex items-center gap-1">
      <Button
        variant="outline"
        size="sm"
        onClick={() => onGenerateSql(batch)}
        className="h-8"
        title="Generate SQL for this batch"
      >
        <Plus className="h-4 w-4 mr-1" />
        Generate
      </Button>

      <Button
        variant="ghost"
        size="sm"
        onClick={() => onShowProperties(batch)}
        className="h-8 w-8 p-0"
        title="View batch properties"
      >
        <Info className="h-4 w-4" />
      </Button>
    </div>
  )
}

export function BatchSqlTable({
  batches,
  isLoading,
  onViewSql,
  onGenerateSql,
  onRegenerateSql,
  onDeleteSql,
  onShowProperties,
  pendingBatchId,
  hasFilters = false,
}: BatchSqlTableProps) {
  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-8">
        <Loader2 className="h-8 w-8 animate-spin text-gray-400" />
        <span className="ml-3 text-sm text-gray-500">Loading batches...</span>
      </div>
    )
  }

  if (batches.length === 0) {
    return (
      <div className="rounded-lg border border-gray-200 bg-gray-50 p-8 text-center">
        <Database className="mx-auto h-12 w-12 text-gray-400" />
        <p className="mt-4 text-sm text-gray-500">No completed batches</p>
        <p className="text-xs text-gray-400">
          {hasFilters
            ? 'Try adjusting your filters.'
            : 'Completed batches will appear here for SQL generation.'}
        </p>
      </div>
    )
  }

  return (
    <div className="rounded-md border">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Site</TableHead>
            <TableHead>Completed</TableHead>
            <TableHead>Files</TableHead>
            <TableHead>Size</TableHead>
            <TableHead>Result</TableHead>
            <TableHead className="text-right">Actions</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {batches.map((batch) => (
            <TableRow key={batch.batchId}>
              <TableCell className="font-medium">{batch.siteDomain}</TableCell>
              <TableCell className="text-sm text-gray-500">
                {formatTimestamp(batch.completedAt)}
              </TableCell>
              <TableCell>{batch.fileCount}</TableCell>
              <TableCell>{formatFileSize(batch.totalSizeBytes)}</TableCell>
              <TableCell>
                <ResultCell batch={batch} />
              </TableCell>
              <TableCell className="text-right">
                <BatchActions
                  batch={batch}
                  onViewSql={onViewSql}
                  onGenerateSql={onGenerateSql}
                  onRegenerateSql={onRegenerateSql}
                  onDeleteSql={onDeleteSql}
                  onShowProperties={onShowProperties}
                  isPending={pendingBatchId === batch.batchId}
                />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

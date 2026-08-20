/**
 * Table component for displaying SQL generation history.
 *
 * @module features/plugin-history/ui/GenerationListTable
 */

import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/shared/ui/ui/table'
import { Button } from '@/shared/ui/ui/button'
import { severityTokens } from '@/shared/ui/tokens';
import { Badge } from '@/shared/ui/ui/badge'
import { Skeleton } from '@/shared/ui/ui/skeleton'
import {
  ChevronLeft,
  ChevronRight,
  Download,
  Eye,
  FileText,
} from 'lucide-react'
import type { SqlGenerationSummary, SqlGenerationListResponse } from '../model/types'

interface GenerationListTableProps {
  data?: SqlGenerationListResponse
  isLoading: boolean
  onViewContent: (generation: SqlGenerationSummary) => void
  onDownload: (generation: SqlGenerationSummary) => void
  onPageChange: (page: number) => void
}

function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${sizes[i]}`
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
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

export function GenerationListTable({
  data,
  isLoading,
  onViewContent,
  onDownload,
  onPageChange,
}: GenerationListTableProps) {
  if (isLoading) {
    return (
      <div className="space-y-2">
        {Array.from({ length: 5 }).map((_, i) => (
          <Skeleton key={i} className="h-12 w-full" />
        ))}
      </div>
    )
  }

  if (!data || data.content.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-12 text-center">
        <FileText className="h-12 w-12 text-muted-foreground mb-4" />
        <h3 className="text-lg font-medium">No SQL generations found</h3>
        <p className="text-sm text-muted-foreground">
          SQL files will appear here after batch processing
        </p>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Site</TableHead>
            <TableHead>Created</TableHead>
            <TableHead className="text-right">Statements</TableHead>
            <TableHead className="text-right">INS/UPD/DEL</TableHead>
            <TableHead className="text-right">Size</TableHead>
            <TableHead className="text-right">Duration</TableHead>
            <TableHead>Status</TableHead>
            <TableHead className="text-right">Actions</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {data.content.map((generation) => (
            <TableRow key={generation.id}>
              <TableCell className="font-medium">
                <div className="flex flex-col">
                  <span>{generation.siteDomain}</span>
                  {generation.isInitialLoad && (
                    <span className="text-xs text-muted-foreground">
                      Initial load
                    </span>
                  )}
                </div>
              </TableCell>
              <TableCell>{formatDate(generation.createdAt)}</TableCell>
              <TableCell className="text-right font-mono">
                {generation.statementCount.toLocaleString()}
              </TableCell>
              <TableCell className="text-right font-mono text-sm">
                <span className="tabular-nums" style={{ color: severityTokens.healthy.text }}>{generation.insertCount}</span>
                {' / '}
                <span className="tabular-nums text-brand">{generation.updateCount}</span>
                {' / '}
                <span className="tabular-nums text-danger-text">{generation.deleteCount}</span>
              </TableCell>
              <TableCell className="text-right">
                {formatBytes(generation.fileSizeBytes)}
              </TableCell>
              <TableCell className="text-right">
                {formatDuration(generation.generationDurationMs)}
              </TableCell>
              <TableCell>
                {generation.superseded ? (
                  <Badge variant="neutral">Superseded</Badge>
                ) : (
                  <Badge variant="success" dot>Active</Badge>
                )}
              </TableCell>
              <TableCell className="text-right">
                <div className="flex justify-end gap-1">
                  <Button
                    variant="ghost"
                    size="icon"
                    onClick={() => onViewContent(generation)}
                    title="View SQL content"
                  >
                    <Eye className="h-4 w-4" />
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon"
                    onClick={() => onDownload(generation)}
                    title="Download SQL file"
                  >
                    <Download className="h-4 w-4" />
                  </Button>
                </div>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>

      {/* Pagination */}
      <div className="flex items-center justify-between">
        <p className="text-sm text-muted-foreground">
          Showing {data.content.length} of {data.totalElements} generations
        </p>
        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            disabled={data.page === 0}
            onClick={() => onPageChange(data.page - 1)}
          >
            <ChevronLeft className="h-4 w-4" />
            Previous
          </Button>
          <span className="text-sm">
            Page {data.page + 1} of {data.totalPages}
          </span>
          <Button
            variant="outline"
            size="sm"
            disabled={data.page >= data.totalPages - 1}
            onClick={() => onPageChange(data.page + 1)}
          >
            Next
            <ChevronRight className="h-4 w-4" />
          </Button>
        </div>
      </div>
    </div>
  )
}

/**
 * Modal component for viewing paginated SQL content.
 *
 * @module features/plugin-history/ui/SqlContentViewer
 */

import { useState } from 'react'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/shared/ui/ui/dialog'
import { Button } from '@/shared/ui/ui/button'
import { Skeleton } from '@/shared/ui/ui/skeleton'
import { ChevronLeft, ChevronRight, Copy, Check, Download } from 'lucide-react'
import { useSqlContentQuery, useDownloadSqlFile } from '../api/plugin-history.queries'
import type { SqlGenerationSummary } from '../model/types'

interface SqlContentViewerProps {
  pluginId: string
  accountId: string
  generation: SqlGenerationSummary | null
  isOpen: boolean
  onClose: () => void
}

export function SqlContentViewer({
  pluginId,
  accountId,
  generation,
  isOpen,
  onClose,
}: SqlContentViewerProps) {
  const [page, setPage] = useState(0)
  const [copied, setCopied] = useState(false)

  const { data, isLoading } = useSqlContentQuery(
    {
      pluginId,
      accountId,
      generationId: generation?.id ?? '',
      page,
      size: 50,
    },
    isOpen && !!generation
  )

  const downloadMutation = useDownloadSqlFile()

  const handleCopy = async () => {
    if (data?.statements) {
      await navigator.clipboard.writeText(data.statements.join('\n\n'))
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    }
  }

  const handleDownload = () => {
    if (generation) {
      downloadMutation.mutate({
        pluginId,
        accountId,
        generationId: generation.id,
      })
    }
  }

  if (!generation) return null

  return (
    <Dialog open={isOpen} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-w-4xl max-h-[80vh] flex flex-col">
        <DialogHeader>
          <DialogTitle>SQL Content</DialogTitle>
          <DialogDescription>
            {generation.siteDomain} - {generation.statementCount} statements
          </DialogDescription>
        </DialogHeader>

        <div className="flex items-center justify-between border-b pb-2">
          <div className="text-sm text-muted-foreground">
            Page {(data?.page ?? 0) + 1} of {data?.totalPages ?? 1}
          </div>
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={handleCopy}
              disabled={!data?.statements?.length}
            >
              {copied ? (
                <>
                  <Check className="h-4 w-4 mr-1" />
                  Copied
                </>
              ) : (
                <>
                  <Copy className="h-4 w-4 mr-1" />
                  Copy Page
                </>
              )}
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={handleDownload}
              disabled={downloadMutation.isPending}
            >
              <Download className="h-4 w-4 mr-1" />
              Download All
            </Button>
          </div>
        </div>

        <div className="flex-1 min-h-[400px] overflow-auto">
          {isLoading ? (
            <div className="space-y-2 p-4">
              {Array.from({ length: 5 }).map((_, i) => (
                <Skeleton key={i} className="h-16 w-full" />
              ))}
            </div>
          ) : (
            <div className="p-4 space-y-4">
              {data?.statements.map((statement, index) => (
                <pre
                  key={index}
                  className="bg-muted p-3 rounded-md text-sm font-mono overflow-x-auto whitespace-pre-wrap"
                >
                  <code>{statement}</code>
                </pre>
              ))}
              {data?.statements.length === 0 && (
                <p className="text-center text-muted-foreground py-8">
                  No statements on this page
                </p>
              )}
            </div>
          )}
        </div>

        <div className="flex items-center justify-between pt-2 border-t">
          <Button
            variant="outline"
            size="sm"
            disabled={!data?.hasPrevious}
            onClick={() => setPage((p) => Math.max(0, p - 1))}
          >
            <ChevronLeft className="h-4 w-4 mr-1" />
            Previous
          </Button>
          <span className="text-sm text-muted-foreground">
            {data?.totalStatements ?? 0} total statements
          </span>
          <Button
            variant="outline"
            size="sm"
            disabled={!data?.hasNext}
            onClick={() => setPage((p) => p + 1)}
          >
            Next
            <ChevronRight className="h-4 w-4 ml-1" />
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  )
}

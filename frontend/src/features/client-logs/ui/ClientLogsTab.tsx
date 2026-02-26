/**
 * ClientLogsTab - Paginated list of client diagnostic logs.
 *
 * Shows log entries with download capability, pagination, and empty state.
 *
 * Feature: 021-unified-upload-api (T046, US11)
 */

import { useState } from 'react';
import { Button } from '@/shared/ui/ui/button';
import { Skeleton } from '@/shared/ui/ui/skeleton';
import { Alert, AlertDescription } from '@/shared/ui/ui/alert';
import { AlertCircle, ChevronLeft, ChevronRight } from 'lucide-react';
import { toast } from 'sonner';
import { ClientLogEntry } from './ClientLogEntry';
import { useClientLogs, useClientLogDownload } from '../api/clientLogsQueries';

interface ClientLogsTabProps {
  siteId: string;
}

const PAGE_SIZES = [20, 50, 100] as const;

export function ClientLogsTab({ siteId }: ClientLogsTabProps) {
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState<number>(20);

  const { data, isLoading, error } = useClientLogs(siteId, page, pageSize);
  const downloadMutation = useClientLogDownload();

  const handleDownload = (logId: string) => {
    downloadMutation.mutate(
      { siteId, logId },
      {
        onError: (err: any) => {
          toast.error(err?.response?.data?.message || 'Failed to download log file');
        },
      },
    );
  };

  const handlePageSizeChange = (newSize: number) => {
    setPageSize(newSize);
    setPage(0);
  };

  if (isLoading) {
    return (
      <div className="space-y-3">
        {[1, 2, 3].map((i) => (
          <Skeleton key={i} className="h-20 w-full" />
        ))}
      </div>
    );
  }

  if (error) {
    return (
      <Alert variant="destructive">
        <AlertCircle className="h-4 w-4" />
        <AlertDescription>
          Failed to load client logs. Please try again later.
        </AlertDescription>
      </Alert>
    );
  }

  if (!data || data.content.length === 0) {
    return (
      <Alert>
        <AlertDescription>
          No diagnostic logs uploaded yet.
        </AlertDescription>
      </Alert>
    );
  }

  const totalPages = Math.ceil(data.totalElements / pageSize);

  return (
    <div className="space-y-3">
      {data.content.map((log) => (
        <ClientLogEntry
          key={log.logId}
          log={log}
          onDownload={handleDownload}
          isDownloading={downloadMutation.isPending}
        />
      ))}

      {/* Pagination controls */}
      <div className="flex items-center justify-between pt-2">
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <span>Show</span>
          <select
            value={pageSize}
            onChange={(e) => handlePageSizeChange(Number(e.target.value))}
            className="h-8 rounded border border-gray-200 px-2 text-xs"
          >
            {PAGE_SIZES.map((size) => (
              <option key={size} value={size}>{size}</option>
            ))}
          </select>
          <span>of {data.totalElements} logs</span>
        </div>

        <div className="flex items-center gap-1">
          <Button
            variant="outline"
            size="sm"
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            disabled={page === 0}
          >
            <ChevronLeft className="h-4 w-4" />
          </Button>
          <span className="px-2 text-sm text-muted-foreground">
            {page + 1} / {totalPages}
          </span>
          <Button
            variant="outline"
            size="sm"
            onClick={() => setPage((p) => p + 1)}
            disabled={page >= totalPages - 1}
          >
            <ChevronRight className="h-4 w-4" />
          </Button>
        </div>
      </div>
    </div>
  );
}

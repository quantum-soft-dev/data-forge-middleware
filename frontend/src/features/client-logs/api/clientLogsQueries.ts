/**
 * TanStack Query hooks for client diagnostic logs.
 *
 * Feature: 021-unified-upload-api (T043, US11)
 */

import { useQuery, useMutation } from '@tanstack/react-query';
import { listClientLogs, downloadClientLog } from './clientLogsApi';

export const clientLogKeys = {
  all: ['client-logs'] as const,
  list: (siteId: string, page: number, size: number) =>
    [...clientLogKeys.all, 'list', siteId, page, size] as const,
};

/**
 * Fetch paginated client diagnostic logs for a site.
 */
export function useClientLogs(siteId: string, page: number = 0, size: number = 20) {
  return useQuery({
    queryKey: clientLogKeys.list(siteId, page, size),
    queryFn: () => listClientLogs(siteId, page, size),
    enabled: !!siteId,
    staleTime: 30000,
  });
}

/**
 * Download a client log file via presigned URL.
 * Opens the URL in a new tab on success.
 */
export function useClientLogDownload() {
  return useMutation({
    mutationFn: ({ siteId, logId }: { siteId: string; logId: string }) =>
      downloadClientLog(siteId, logId),
    onSuccess: (data) => {
      window.open(data.url, '_blank');
    },
  });
}

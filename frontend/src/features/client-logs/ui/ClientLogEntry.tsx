/**
 * ClientLogEntry - Single client diagnostic log row component.
 *
 * Displays filename, file size, client version, OS, tags, description,
 * upload date, and a download button.
 *
 * Feature: 021-unified-upload-api (T045, US11)
 */

import { Badge } from '@/shared/ui/ui/badge';
import { Button } from '@/shared/ui/ui/button';
import { Download } from 'lucide-react';
import { format } from 'date-fns';
import type { ClientLog } from '../model/types';

interface ClientLogEntryProps {
  log: ClientLog;
  onDownload: (logId: string) => void;
  isDownloading?: boolean;
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function ClientLogEntry({ log, onDownload, isDownloading = false }: ClientLogEntryProps) {
  return (
    <div className="flex items-start justify-between gap-4 rounded-lg border p-3">
      <div className="flex-1 min-w-0 space-y-1">
        <div className="flex items-center gap-2 flex-wrap">
          <span className="font-medium text-sm truncate">{log.filename}</span>
          <span className="text-xs text-muted-foreground">{formatFileSize(log.fileSize)}</span>
        </div>

        <div className="flex items-center gap-3 text-xs text-muted-foreground flex-wrap">
          {log.clientVersion && <span>v{log.clientVersion}</span>}
          {log.os && <span>{log.os}</span>}
          <span>{format(new Date(log.uploadedAt), 'PPp')}</span>
        </div>

        {log.tags && log.tags.length > 0 && (
          <div className="flex gap-1 flex-wrap">
            {log.tags.map((tag) => (
              <Badge key={tag} variant="secondary" className="text-xs">
                {tag}
              </Badge>
            ))}
          </div>
        )}

        {log.description && (
          <p className="text-xs text-muted-foreground line-clamp-2">{log.description}</p>
        )}
      </div>

      <Button
        variant="outline"
        size="sm"
        onClick={() => onDownload(log.logId)}
        disabled={isDownloading}
        title="Download log file"
      >
        <Download className="h-4 w-4" />
      </Button>
    </div>
  );
}

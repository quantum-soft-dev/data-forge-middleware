/**
 * RequestLogsDialog - Confirmation dialog for requesting client diagnostic logs.
 *
 * Accepts an optional message input before confirming. Button disabled when
 * requestLogs directive is already active.
 *
 * Feature: 021-unified-upload-api (T049, US10)
 */

import { useState } from 'react';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/shared/ui/ui/alert-dialog';
import { Input } from '@/shared/ui/ui/input';
import { Label } from '@/shared/ui/ui/label';

interface RequestLogsDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  siteName: string;
  onConfirm: (message?: string) => void;
  isLoading?: boolean;
}

export function RequestLogsDialog({
  open,
  onOpenChange,
  siteName,
  onConfirm,
  isLoading = false,
}: RequestLogsDialogProps) {
  const [message, setMessage] = useState('');

  const handleConfirm = () => {
    onConfirm(message.trim() || undefined);
    setMessage('');
  };

  const handleOpenChange = (open: boolean) => {
    if (!open) {
      setMessage('');
    }
    onOpenChange(open);
  };

  return (
    <AlertDialog open={open} onOpenChange={handleOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Request Client Logs</AlertDialogTitle>
          <AlertDialogDescription className="space-y-2">
            <p>
              This will instruct <strong>{siteName}</strong> to upload diagnostic logs
              on its next sync.
            </p>
            <p className="text-muted-foreground">
              The client will upload log files when it next contacts the server.
            </p>
          </AlertDialogDescription>
        </AlertDialogHeader>

        <div className="py-2">
          <Label htmlFor="request-logs-message">Message (optional)</Label>
          <Input
            id="request-logs-message"
            placeholder="e.g., Please include logs from the last 24 hours"
            value={message}
            onChange={(e) => setMessage(e.target.value)}
            maxLength={1000}
            className="mt-1"
          />
        </div>

        <AlertDialogFooter>
          <AlertDialogCancel disabled={isLoading}>Cancel</AlertDialogCancel>
          <AlertDialogAction
            onClick={handleConfirm}
            disabled={isLoading}
          >
            {isLoading ? 'Sending...' : 'Request Logs'}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

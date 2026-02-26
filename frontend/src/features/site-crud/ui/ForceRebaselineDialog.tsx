/**
 * ForceRebaselineDialog - Confirmation dialog for forcing a full data rebaseline.
 *
 * Requires a reason input before confirming. Shows warning about the impact.
 *
 * Feature: 021-unified-upload-api (T039, US9)
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

interface ForceRebaselineDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  siteName: string;
  onConfirm: (reason: string) => void;
  isLoading?: boolean;
}

export function ForceRebaselineDialog({
  open,
  onOpenChange,
  siteName,
  onConfirm,
  isLoading = false,
}: ForceRebaselineDialogProps) {
  const [reason, setReason] = useState('');

  const handleConfirm = () => {
    if (reason.trim()) {
      onConfirm(reason.trim());
      setReason('');
    }
  };

  const handleOpenChange = (open: boolean) => {
    if (!open) {
      setReason('');
    }
    onOpenChange(open);
  };

  return (
    <AlertDialog open={open} onOpenChange={handleOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Force Rebaseline</AlertDialogTitle>
          <AlertDialogDescription className="space-y-2">
            <p>
              This will instruct <strong>{siteName}</strong> to upload a full CSV baseline
              on its next sync instead of incremental deltas.
            </p>
            <p className="text-orange-600 font-medium">
              The client will re-upload all data files. This may take longer than a regular sync.
            </p>
          </AlertDialogDescription>
        </AlertDialogHeader>

        <div className="py-2">
          <Label htmlFor="rebaseline-reason">Reason (required)</Label>
          <Input
            id="rebaseline-reason"
            placeholder="e.g., Data corruption detected in batch #42"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            maxLength={1000}
            className="mt-1"
          />
        </div>

        <AlertDialogFooter>
          <AlertDialogCancel disabled={isLoading}>Cancel</AlertDialogCancel>
          <AlertDialogAction
            onClick={handleConfirm}
            disabled={!reason.trim() || isLoading}
            className="bg-orange-500 hover:bg-orange-600"
          >
            {isLoading ? 'Sending...' : 'Force Rebaseline'}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

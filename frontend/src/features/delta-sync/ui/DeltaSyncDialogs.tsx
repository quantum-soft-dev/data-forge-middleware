/**
 * Confirmation dialogs for the Delta Sync actions (023, F9).
 *
 * shadcn AlertDialog per design handoff "Interactions" — copy is final.
 * Never window.confirm.
 */

import { AlertTriangle } from 'lucide-react';
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

interface ConfirmDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onConfirm: () => void;
}

export function RebuildCheckpointDialog({ open, onOpenChange, onConfirm }: ConfirmDialogProps) {
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Rebuild checkpoint now?</AlertDialogTitle>
          <AlertDialogDescription>
            A checkpoint rebuild will be scheduled outside the regular schedule. This can be a
            heavy operation on large tables.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction onClick={onConfirm} className="bg-[#3C82D8] hover:bg-[#3676C4]">
            Rebuild now
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

export function RebaselineDialog({ open, onOpenChange, onConfirm }: ConfirmDialogProps) {
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Request full re-baseline?</AlertDialogTitle>
          <AlertDialogDescription asChild>
            <div
              className="flex items-start gap-2 rounded-lg border p-3 text-sm"
              style={{
                background: 'rgba(239,68,68,0.08)',
                borderColor: 'rgba(239,68,68,0.25)',
                color: '#B91C1C',
              }}
            >
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" strokeWidth={1.5} />
              <span>
                The client will re-send a full snapshot on next connect. This may take a while for
                large datasets and cannot be cancelled once the client starts.
              </span>
            </div>
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction onClick={onConfirm} className="bg-[#EF4444] hover:bg-[#DC2626]">
            Request re-baseline
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

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
          <AlertDialogAction onClick={onConfirm} className="bg-brand hover:bg-brand-hover">
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
              className="flex items-start gap-2 rounded-lg border p-3 text-sm text-danger-text"
              style={{
                background: 'rgba(239,68,68,0.08)',
                borderColor: 'rgba(239,68,68,0.25)',
              }}
            >
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" strokeWidth={1.5} />
              <span>
                On its next connect the client re-uploads the entire dataset as one full snapshot —
                every row of every table, which on a large site can take hours. You can take the
                request back, but only reliably until the client picks it up; once it starts
                uploading, it must run to completion.
              </span>
            </div>
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction onClick={onConfirm} className="bg-danger-solid hover:bg-danger-solid-hover">
            Request re-baseline
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

/**
 * Takes a pending re-baseline request back (#84). Both footer buttons say what they do —
 * a bare "Cancel" would be ambiguous in a dialog whose subject is a cancellation.
 */
export function CancelRebaselineDialog({ open, onOpenChange, onConfirm }: ConfirmDialogProps) {
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Cancel the re-baseline request?</AlertDialogTitle>
          <AlertDialogDescription>
            The pending full snapshot is called off: the watermark and checkpoints are left as they
            are and the client resumes ordinary delta from where it stopped. This has no effect once
            the client has already started the snapshot.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Keep request</AlertDialogCancel>
          <AlertDialogAction onClick={onConfirm} className="bg-brand hover:bg-brand-hover">
            Cancel re-baseline
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

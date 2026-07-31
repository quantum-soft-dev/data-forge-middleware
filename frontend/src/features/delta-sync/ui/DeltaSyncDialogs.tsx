/**
 * Confirmation dialogs for the Delta Sync actions (023, F9).
 *
 * shadcn AlertDialog per design handoff "Interactions" — copy is final.
 * Never window.confirm.
 */

import { useState } from 'react';
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
import { Input } from '@/shared/ui/ui/input';

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

interface WipeHistoryDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Typed back by the operator to unlock the button; the backend re-checks it. */
  siteName: string;
  onConfirm: (confirm: string) => void;
  wiping?: boolean;
}

/**
 * Irreversibly destroys a site's history (#89).
 *
 * <p>Confirm-by-typing rather than a plain "are you sure": the damage here is total, unrecoverable
 * and invisible until a client next connects, so a mis-click on the wrong site would not announce
 * itself. The button stays disabled until the typed text is exactly the site's name.</p>
 */
export function WipeHistoryDialog({
  open,
  onOpenChange,
  siteName,
  onConfirm,
  wiping = false,
}: WipeHistoryDialogProps) {
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        {/* The typed confirmation lives one level down, inside the content Radix unmounts on close,
            so a dialog dismissed and reopened never comes back already unlocked. */}
        <WipeHistoryDialogBody siteName={siteName} onConfirm={onConfirm} wiping={wiping} />
      </AlertDialogContent>
    </AlertDialog>
  );
}

function WipeHistoryDialogBody({
  siteName,
  onConfirm,
  wiping,
}: Pick<WipeHistoryDialogProps, 'siteName' | 'onConfirm'> & { wiping: boolean }) {
  const [typed, setTyped] = useState('');
  const confirmed = typed === siteName;

  return (
    <>
      <AlertDialogHeader>
        <AlertDialogTitle>Wipe all history of {siteName}?</AlertDialogTitle>
        <AlertDialogDescription asChild>
          <div className="space-y-3">
            <div
              className="flex items-start gap-2 rounded-lg border p-3 text-sm text-danger-text"
              style={{
                background: 'rgba(239,68,68,0.08)',
                borderColor: 'rgba(239,68,68,0.25)',
              }}
            >
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" strokeWidth={1.5} />
              <span>
                This permanently deletes every batch, uploaded file, changelog segment, checkpoint,
                error log and generated SQL of this site — including its table schema. It cannot be
                undone.
              </span>
            </div>
            <p className="text-sm text-ink-secondary">
              The site itself and its credentials stay. On its next connect the client re-submits
              its schema and re-uploads the entire dataset as one full snapshot. Bit BI baselines
              re-capture automatically once the first checkpoint after the wipe is built.
            </p>
            <p className="text-sm text-ink-secondary">
              Type <span className="font-medium text-ink">{siteName}</span> to confirm.
            </p>
            <Input
              value={typed}
              onChange={(event) => setTyped(event.target.value)}
              placeholder={siteName}
              aria-label="Confirm the site name"
              autoComplete="off"
            />
          </div>
        </AlertDialogDescription>
      </AlertDialogHeader>
      <AlertDialogFooter>
        <AlertDialogCancel>Keep history</AlertDialogCancel>
        <AlertDialogAction
          onClick={() => onConfirm(typed)}
          disabled={!confirmed || wiping}
          className="bg-danger-solid hover:bg-danger-solid-hover"
        >
          {wiping ? 'Wiping…' : 'Wipe history'}
        </AlertDialogAction>
      </AlertDialogFooter>
    </>
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

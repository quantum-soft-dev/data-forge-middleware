/**
 * Danger zone of the site-detail page (035, issue #89).
 *
 * Separated from the Delta Sync tab on purpose: the wipe is not a sync operation and is the only
 * irreversible action on this page, so it does not belong next to the ones an operator triggers
 * routinely.
 */

import { useState } from 'react';
import { toast } from 'sonner';
import { SiteHistoryWipeConflictError, type DeltaApiScope } from '../api/deltaSyncApi';
import { useWipeSiteHistory } from '../api/queries';
import { WipeHistoryDialog } from './DeltaSyncDialogs';
import { Button } from '@/shared/ui/ui/button';

interface DangerZoneCardProps {
  siteId: string;
  /** The identity the operator must type back — the site name shown in the page title. */
  siteName: string;
  scope: DeltaApiScope;
}

export function DangerZoneCard({ siteId, siteName, scope }: DangerZoneCardProps) {
  const [dialogOpen, setDialogOpen] = useState(false);
  const wipe = useWipeSiteHistory(siteId, scope);

  const handleConfirm = (confirm: string) => {
    wipe.mutate(confirm, {
      onSuccess: (result) => {
        setDialogOpen(false);
        toast.success(
          `History wiped — ${result.deletedBatches} batches, ${result.deletedFiles} files removed. ` +
            `The client will re-upload a full snapshot on its next connect.`,
        );
        if (result.prefixesNotSwept > 0) {
          // A skipped prefix is not an object count — quoting s3DeleteErrors here would hide that
          // the whole prefix (including every _frame/ reload frame) survived. Repeating the wipe
          // is safe and is how the leftover objects get swept (issue #122).
          toast.warning(
            'Some storage prefixes could not be swept completely. Repeat the wipe — it is safe and will finish the cleanup.',
          );
        } else if (result.s3DeleteErrors > 0) {
          // The rows are gone either way; this is about orphaned objects, not lost data.
          toast.warning(
            `${result.s3DeleteErrors} storage object(s) could not be deleted and were left behind.`,
          );
        }
      },
      onError: (error) => {
        if (error instanceof SiteHistoryWipeConflictError) {
          toast.warning(
            error.status === 'session-in-progress'
              ? 'A sync session is currently running — stop the client and retry.'
              : 'A sync session committed while the wipe ran, so nothing was deleted. Try again.',
          );
          return;
        }
        toast.error('Something went wrong. Please try again.');
      },
    });
  };

  return (
    <section className="mt-6 rounded-xl border p-4" style={{ borderColor: 'rgba(239,68,68,0.25)' }}>
      <h2 className="text-sm font-medium text-danger-text">Danger zone</h2>
      <div className="mt-3 flex flex-wrap items-center justify-between gap-3">
        <div className="max-w-2xl">
          <p className="text-sm font-medium text-ink">Wipe all history</p>
          <p className="mt-0.5 text-sm text-ink-secondary">
            Permanently deletes every batch, uploaded file, changelog segment, checkpoint, error log
            and generated SQL of this site, including its table schema. The site and its credentials
            stay; the client starts over from a full snapshot. This cannot be undone.
          </p>
        </div>
        <Button
          variant="destructive-outline"
          onClick={() => setDialogOpen(true)}
          disabled={wipe.isPending}
        >
          Wipe history
        </Button>
      </div>

      <WipeHistoryDialog
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        siteName={siteName}
        onConfirm={handleConfirm}
        wiping={wipe.isPending}
      />
    </section>
  );
}

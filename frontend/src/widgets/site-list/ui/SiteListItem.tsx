/**
 * SiteListItem - Individual site row component with actions.
 *
 * Features:
 * - Site information display (domain, name, status)
 * - Activate/Deactivate buttons
 * - Delete button with confirmation
 * - Status badge (Active/Inactive)
 *
 * Feature: 007-adding-a-site (T036, US1, US2, US3)
 */

import { useEffect, useState } from 'react';
import { Button } from '@/shared/ui/ui/button';
import { Badge } from '@/shared/ui/ui/badge';
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
import { Card, CardContent } from '@/shared/ui/ui/card';
import { Input } from '@/shared/ui/ui/input';
import { Trash2, Power, PowerOff } from 'lucide-react';
import type { Site } from '@/entities/site';
import { format } from 'date-fns';

interface SiteListItemProps {
  site: Site;
  /** Right-aligned status content (023, F11 — sync health pill). Wraps under the name on narrow widths. */
  statusSlot?: React.ReactNode;
  /** Open the site-detail page (023, F3). Makes the site info block clickable. */
  onOpen?: (siteId: string) => void;
  onActivate?: (siteId: string) => void;
  onDeactivate?: (siteId: string) => void;
  onDelete?: (siteId: string) => void;
  onUpdateRetention?: (siteId: string, retentionDays: number) => void;
  showRetentionControls?: boolean;
  isLoading?: boolean;
}

export function SiteListItem({
  site,
  statusSlot,
  onOpen,
  onActivate,
  onDeactivate,
  onDelete,
  onUpdateRetention,
  showRetentionControls = false,
  isLoading = false,
}: SiteListItemProps) {
  const [showDeleteDialog, setShowDeleteDialog] = useState(false);
  const [showDeactivateDialog, setShowDeactivateDialog] = useState(false);
  const [retentionInput, setRetentionInput] = useState(String(site.retentionDays));

  useEffect(() => {
    setRetentionInput(String(site.retentionDays));
  }, [site.retentionDays]);

  const handleStatusToggle = () => {
    if (site.isActive) {
      setShowDeactivateDialog(true);
    } else {
      onActivate?.(site.id);
    }
  };

  const handleDeactivateConfirm = () => {
    onDeactivate?.(site.id);
    setShowDeactivateDialog(false);
  };

  const handleDelete = () => {
    onDelete?.(site.id);
    setShowDeleteDialog(false);
  };

  const handleRetentionSave = () => {
    if (!onUpdateRetention) return;
    const parsedRetention = Number(retentionInput);
    if (!Number.isFinite(parsedRetention) || parsedRetention <= 0) return;
    onUpdateRetention(site.id, parsedRetention);
  };

  return (
    <>
      <Card>
        <CardContent className="p-4">
          <div className="flex flex-wrap items-center justify-between gap-4">
            {/* Site info (clickable when onOpen is provided) */}
            <div
              className={`flex-1 min-w-0${onOpen ? ' cursor-pointer' : ''}`}
              onClick={onOpen ? () => onOpen(site.id) : undefined}
              role={onOpen ? 'link' : undefined}
              aria-label={onOpen ? `Open ${site.siteName}` : undefined}
            >
              <div className="flex items-center gap-2 mb-1">
                <h3 className="truncate text-[15px] font-medium tracking-[-0.24px] text-ink">
                  {site.siteName}
                </h3>
                <Badge variant={site.isActive ? 'success' : 'neutral'} dot>
                  {site.isActive ? 'Active' : 'Inactive'}
                </Badge>
                <Badge variant="neutral">
                  {site.siteType === 'POSTGRES_CDC' ? 'Postgres CDC' : 'DBF'}
                </Badge>
                <Badge variant={site.clientApiVersion === 'V2' ? 'info' : 'neutral'}>
                  {site.clientApiVersion === 'V2' ? 'Delta v2' : 'v1'}
                </Badge>
              </div>
              <p className="text-sm text-muted-foreground truncate">{site.name}</p>
              <p className="text-xs text-muted-foreground mt-1">
                Created {format(new Date(site.createdAt), 'PPP')}
              </p>
              {showRetentionControls && (
                <div className="mt-2 flex items-center gap-2 text-xs text-muted-foreground">
                  <span>Retention (days):</span>
                  <Input
                    type="number"
                    min={1}
                    max={3650}
                    className="h-7 w-20 px-2 text-xs"
                    value={retentionInput}
                    onChange={(event) => setRetentionInput(event.target.value)}
                    disabled={isLoading}
                  />
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={handleRetentionSave}
                    disabled={
                      isLoading ||
                      !Number.isFinite(Number(retentionInput)) ||
                      Number(retentionInput) <= 0 ||
                      Number(retentionInput) === site.retentionDays
                    }
                  >
                    Save
                  </Button>
                </div>
              )}
            </div>

            {/* Sync health (023, F11) — right-aligned, wraps under the name before the actions */}
            {statusSlot !== undefined && (
              <div className="order-3 basis-full sm:order-none sm:basis-auto sm:ml-auto flex items-center">
                {statusSlot}
              </div>
            )}

            {/* Actions */}
            <div className="flex items-center gap-2">
              {/* Activate/Deactivate button */}
              <Button
                variant="outline"
                size="sm"
                onClick={handleStatusToggle}
                disabled={isLoading}
                title={site.isActive ? 'Deactivate site' : 'Activate site'}
              >
                {site.isActive ? (
                  <>
                    <PowerOff className="mr-1 h-4 w-4" />
                    Deactivate
                  </>
                ) : (
                  <>
                    <Power className="mr-1 h-4 w-4" />
                    Activate
                  </>
                )}
              </Button>

              {/* Delete button */}
              <Button
                variant="destructive-outline"
                size="sm"
                onClick={() => setShowDeleteDialog(true)}
                disabled={isLoading}
                title="Delete site"
              >
                <Trash2 className="mr-1 h-4 w-4" />
                Delete
              </Button>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Deactivate confirmation dialog */}
      <AlertDialog open={showDeactivateDialog} onOpenChange={setShowDeactivateDialog}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Deactivate Site</AlertDialogTitle>
            <AlertDialogDescription>
              Are you sure you want to deactivate <strong>{site.siteName}</strong>?
              <br />
              <br />
              The site will be temporarily disabled. All historical data, batches, errors, and uploads will be preserved. You can reactivate the site at any time.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={handleDeactivateConfirm} className="bg-warn-solid hover:bg-warn-solid-hover">
              Deactivate Site
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* Delete confirmation dialog */}
      <AlertDialog open={showDeleteDialog} onOpenChange={setShowDeleteDialog}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Permanently Delete Site</AlertDialogTitle>
            <AlertDialogDescription className="space-y-2">
              <p className="font-medium text-danger-text">
                ⚠️ WARNING: This action cannot be undone!
              </p>
              <p>
                Are you sure you want to permanently delete <strong>{site.siteName}</strong>?
              </p>
              <p>
                This will permanently delete:
              </p>
              <ul className="list-disc list-inside pl-2 space-y-1">
                <li>The site configuration</li>
                <li>All batch history</li>
                <li>All uploaded files</li>
                <li>All error logs</li>
                <li>All associated data</li>
              </ul>
              <p className="font-semibold mt-2">
                If you want to temporarily disable the site, use "Deactivate" instead.
              </p>
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={handleDelete} className="bg-danger-solid hover:bg-danger-solid-hover">
              Yes, Permanently Delete
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}

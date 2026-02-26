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
import { useNavigate } from '@tanstack/react-router';
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
import { CheckCircle2, XCircle, Trash2, Power, PowerOff } from 'lucide-react';
import type { Site } from '@/entities/site';
import { format } from 'date-fns';

interface SiteListItemProps {
  site: Site;
  onActivate?: (siteId: string) => void;
  onDeactivate?: (siteId: string) => void;
  onDelete?: (siteId: string) => void;
  onUpdateRetention?: (siteId: string, retentionDays: number) => void;
  showRetentionControls?: boolean;
  isLoading?: boolean;
}

export function SiteListItem({
  site,
  onActivate,
  onDeactivate,
  onDelete,
  onUpdateRetention,
  showRetentionControls = false,
  isLoading = false,
}: SiteListItemProps) {
  const navigate = useNavigate();
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
          <div className="flex items-center justify-between gap-4">
            {/* Site info */}
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 mb-1">
                <h3
                  className="font-semibold text-lg truncate cursor-pointer hover:underline"
                  onClick={() => navigate({ to: '/account/sites/$siteId', params: { siteId: site.id } })}
                >
                  {site.siteName}
                </h3>
                <Badge variant={site.isActive ? 'default' : 'secondary'}>
                  {site.isActive ? (
                    <>
                      <CheckCircle2 className="mr-1 h-3 w-3" />
                      Active
                    </>
                  ) : (
                    <>
                      <XCircle className="mr-1 h-3 w-3" />
                      Inactive
                    </>
                  )}
                </Badge>
                <Badge
                  variant={site.siteType === 'DBF' ? 'secondary' : 'outline'}
                  className={
                    site.siteType === 'POSTGRES_CDC' ? 'border-blue-400 text-blue-600' :
                    site.siteType === 'MSSQL_CDC' ? 'border-sky-400 text-sky-600' :
                    site.siteType === 'DBF_CDC' ? 'border-purple-400 text-purple-600' :
                    ''
                  }
                >
                  {site.siteType === 'POSTGRES_CDC' ? 'Postgres CDC' :
                   site.siteType === 'MSSQL_CDC' ? 'MSSQL CDC' :
                   site.siteType === 'DBF_CDC' ? 'DBF CDC' :
                   'DBF'}
                </Badge>
              </div>
              <p className="text-sm text-muted-foreground truncate">{site.name}</p>
              <p className="text-xs text-muted-foreground mt-1">
                Created {format(new Date(site.createdAt), 'PPP')}
              </p>
              {showRetentionControls && (
                <div className="mt-2 flex items-center gap-2 text-xs text-muted-foreground">
                  <span>Retention (days):</span>
                  <input
                    type="number"
                    min={1}
                    max={3650}
                    className="h-7 w-20 rounded border border-gray-200 px-2 text-xs text-gray-900"
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
                variant="destructive"
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
            <AlertDialogAction onClick={handleDeactivateConfirm} className="bg-orange-500 hover:bg-orange-600">
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
              <p className="font-semibold text-destructive">
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
            <AlertDialogAction onClick={handleDelete} className="bg-destructive hover:bg-destructive/90">
              Yes, Permanently Delete
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}

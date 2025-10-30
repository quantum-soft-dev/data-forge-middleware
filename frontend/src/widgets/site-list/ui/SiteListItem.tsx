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

import { useState } from 'react';
import { Button } from '@/shared/ui/components/button';
import { Badge } from '@/shared/ui/components/badge';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/shared/ui/components/alert-dialog';
import { Card, CardContent } from '@/shared/ui/components/card';
import { CheckCircle2, XCircle, Trash2, Power, PowerOff } from 'lucide-react';
import type { Site } from '@/entities/site';
import { format } from 'date-fns';

interface SiteListItemProps {
  site: Site;
  onActivate?: (siteId: string) => void;
  onDeactivate?: (siteId: string) => void;
  onDelete?: (siteId: string) => void;
  isLoading?: boolean;
}

export function SiteListItem({
  site,
  onActivate,
  onDeactivate,
  onDelete,
  isLoading = false,
}: SiteListItemProps) {
  const [showDeleteDialog, setShowDeleteDialog] = useState(false);

  const handleStatusToggle = () => {
    if (site.isActive) {
      onDeactivate?.(site.id);
    } else {
      onActivate?.(site.id);
    }
  };

  const handleDelete = () => {
    onDelete?.(site.id);
    setShowDeleteDialog(false);
  };

  return (
    <>
      <Card>
        <CardContent className="p-4">
          <div className="flex items-center justify-between gap-4">
            {/* Site info */}
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 mb-1">
                <h3 className="font-semibold text-lg truncate">{site.domain}</h3>
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
              </div>
              <p className="text-sm text-muted-foreground truncate">{site.name}</p>
              <p className="text-xs text-muted-foreground mt-1">
                Created {format(new Date(site.createdAt), 'PPP')}
              </p>
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

      {/* Delete confirmation dialog */}
      <AlertDialog open={showDeleteDialog} onOpenChange={setShowDeleteDialog}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete Site</AlertDialogTitle>
            <AlertDialogDescription>
              Are you sure you want to delete <strong>{site.domain}</strong>?
              <br />
              <br />
              This action will deactivate the site and prevent new uploads. Historical data will
              be preserved.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={handleDelete} className="bg-destructive">
              Delete Site
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}

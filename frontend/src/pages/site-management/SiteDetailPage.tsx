/**
 * SiteDetailPage - Site detail view with actions and tabs.
 *
 * Features:
 * - Site information display
 * - Force Rebaseline button (CDC sites only)
 * - Active directive indicator
 * - Client Logs tab
 *
 * Route: /account/sites/$siteId
 *
 * Feature: 021-unified-upload-api (T040, T047, US9, US11)
 */

import { useState } from 'react';
import { useParams, useNavigate } from '@tanstack/react-router';
import { ArrowLeft, RefreshCw, AlertTriangle } from 'lucide-react';
import { toast } from 'sonner';
import { Header } from '@/widgets/header/Header';
import { Badge } from '@/shared/ui/ui/badge';
import { Button } from '@/shared/ui/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/shared/ui/ui/card';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/shared/ui/ui/tabs';
import { Separator } from '@/shared/ui/ui/separator';
import { Alert, AlertDescription } from '@/shared/ui/ui/alert';
import { Skeleton } from '@/shared/ui/ui/skeleton';
import { useSites, useForceRebaseline } from '@/features/site-crud/model/queries';
import { isCdcSiteType } from '@/entities/site';
import { ForceRebaselineDialog } from '@/features/site-crud/ui/ForceRebaselineDialog';
import { ClientLogsTab } from '@/features/client-logs/ui/ClientLogsTab';
import { format } from 'date-fns';

function getSiteTypeBadge(siteType: string) {
  const labels: Record<string, string> = {
    DBF: 'DBF',
    POSTGRES_CDC: 'Postgres CDC',
    MSSQL_CDC: 'MSSQL CDC',
    DBF_CDC: 'DBF CDC',
  };
  const classes: Record<string, string> = {
    POSTGRES_CDC: 'border-blue-400 text-blue-600',
    MSSQL_CDC: 'border-sky-400 text-sky-600',
    DBF_CDC: 'border-purple-400 text-purple-600',
  };
  return (
    <Badge
      variant={siteType === 'DBF' ? 'secondary' : 'outline'}
      className={classes[siteType] || ''}
    >
      {labels[siteType] || siteType}
    </Badge>
  );
}

export default function SiteDetailPage() {
  const { siteId } = useParams({ from: '/account/sites/$siteId' });
  const navigate = useNavigate();

  const { data: sites, isLoading, error } = useSites();
  const site = sites?.find((s) => s.id === siteId);

  const [showRebaselineDialog, setShowRebaselineDialog] = useState(false);
  const forceRebaselineMutation = useForceRebaseline();

  const handleForceRebaseline = (reason: string) => {
    forceRebaselineMutation.mutate(
      { siteId, reason },
      {
        onSuccess: () => {
          setShowRebaselineDialog(false);
          toast.success('Force rebaseline directive set successfully');
        },
        onError: (err: any) => {
          toast.error(err?.response?.data?.message || 'Failed to set force rebaseline');
        },
      },
    );
  };

  const handleBack = () => {
    navigate({ to: '/account/sites' });
  };

  if (isLoading) {
    return (
      <div className="min-h-screen bg-gray-50">
        <Header />
        <main className="container mx-auto py-6 max-w-4xl px-4">
          <Skeleton className="h-8 w-48 mb-6" />
          <Skeleton className="h-48 w-full" />
        </main>
      </div>
    );
  }

  if (error || !site) {
    return (
      <div className="min-h-screen bg-gray-50">
        <Header />
        <main className="container mx-auto py-6 max-w-4xl px-4">
          <button
            onClick={handleBack}
            className="mb-4 flex items-center gap-2 text-sm text-gray-600 hover:text-gray-900"
          >
            <ArrowLeft className="h-4 w-4" />
            Back to Sites
          </button>
          <Alert variant="destructive">
            <AlertDescription>
              {error ? 'Failed to load site details.' : 'Site not found.'}
            </AlertDescription>
          </Alert>
        </main>
      </div>
    );
  }

  const isCdc = isCdcSiteType(site.siteType);

  return (
    <div className="min-h-screen bg-gray-50">
      <Header />

      <main className="container mx-auto py-6 space-y-6 max-w-4xl px-4">
        {/* Back button */}
        <button
          onClick={handleBack}
          className="flex items-center gap-2 text-sm text-gray-600 hover:text-gray-900"
        >
          <ArrowLeft className="h-4 w-4" />
          Back to Sites
        </button>

        {/* Site header */}
        <div className="flex items-start justify-between gap-4">
          <div>
            <div className="flex items-center gap-2 mb-1">
              <h1 className="text-2xl font-bold tracking-tight">{site.siteName}</h1>
              <Badge variant={site.isActive ? 'default' : 'secondary'}>
                {site.isActive ? 'Active' : 'Inactive'}
              </Badge>
              {getSiteTypeBadge(site.siteType)}
            </div>
            <p className="text-muted-foreground">{site.name}</p>
            <p className="text-xs text-muted-foreground mt-1">
              Created {format(new Date(site.createdAt), 'PPP')}
            </p>
          </div>

          {/* Actions */}
          {isCdc && (
            <Button
              variant="outline"
              size="sm"
              onClick={() => setShowRebaselineDialog(true)}
              className="border-orange-300 text-orange-600 hover:bg-orange-50"
            >
              <RefreshCw className="mr-1 h-4 w-4" />
              Force Rebaseline
            </Button>
          )}
        </div>

        {/* Active directive indicator */}
        {site.forceFullUpload && (
          <Alert className="border-orange-300 bg-orange-50">
            <AlertTriangle className="h-4 w-4 text-orange-600" />
            <AlertDescription className="text-orange-800">
              <strong>Force rebaseline active</strong>
              {site.forceFullUploadReason && ` — ${site.forceFullUploadReason.replace(/_/g, ' ').toLowerCase()}`}
            </AlertDescription>
          </Alert>
        )}

        <Separator />

        {/* Site info card */}
        <Card>
          <CardHeader>
            <CardTitle className="text-lg">Site Information</CardTitle>
          </CardHeader>
          <CardContent>
            <dl className="grid grid-cols-2 gap-4 text-sm">
              <div>
                <dt className="text-muted-foreground">Site ID</dt>
                <dd className="font-mono text-xs mt-0.5">{site.id}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">Account ID</dt>
                <dd className="font-mono text-xs mt-0.5">{site.accountId}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">Retention</dt>
                <dd className="mt-0.5">{site.retentionDays} days</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">Site Type</dt>
                <dd className="mt-0.5">{site.siteType}</dd>
              </div>
            </dl>
          </CardContent>
        </Card>

        {/* Tabs */}
        <Tabs defaultValue="client-logs">
          <TabsList>
            <TabsTrigger value="client-logs">Client Logs</TabsTrigger>
          </TabsList>
          <TabsContent value="client-logs" className="mt-4">
            <ClientLogsTab siteId={siteId} />
          </TabsContent>
        </Tabs>
      </main>

      {/* Force Rebaseline Dialog */}
      <ForceRebaselineDialog
        open={showRebaselineDialog}
        onOpenChange={setShowRebaselineDialog}
        siteName={site.siteName}
        onConfirm={handleForceRebaseline}
        isLoading={forceRebaselineMutation.isPending}
      />
    </div>
  );
}

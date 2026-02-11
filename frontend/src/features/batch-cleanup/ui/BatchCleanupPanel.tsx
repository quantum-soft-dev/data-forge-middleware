import { useMemo, useState } from 'react';
import { useAdminSites } from '@/features/site-crud/model/queries';
import { runBatchCleanup } from '@/entities/batch/api/batchApi';
import type { BatchCleanupResult } from '@/entities/batch/model/types';
import { Button } from '@/shared/ui/ui/button';
import { Card, CardContent } from '@/shared/ui/ui/card';
import { Label } from '@/shared/ui/ui/label';
import { Input } from '@/shared/ui/ui/input';
import { toast } from 'sonner';

interface BatchCleanupPanelProps {
  accountId: string;
}

export function BatchCleanupPanel({ accountId }: BatchCleanupPanelProps) {
  const { data: sites } = useAdminSites(accountId);
  const [siteId, setSiteId] = useState<string>('');
  const [retentionDays, setRetentionDays] = useState<string>('');
  const [olderThan, setOlderThan] = useState<string>('');
  const [limit, setLimit] = useState<string>('1000');
  const [isRunning, setIsRunning] = useState<boolean>(false);
  const [result, setResult] = useState<BatchCleanupResult | null>(null);

  const siteOptions = useMemo(() => {
    if (!sites) return [];
    return sites.map((site) => ({ id: site.id, label: site.siteName }));
  }, [sites]);

  const runCleanup = async (executeDryRun: boolean) => {
    setIsRunning(true);
    setResult(null);

    try {
      const normalizedOlderThan =
        olderThan && olderThan.length === 16 ? `${olderThan}:00` : olderThan || undefined;
      const response = await runBatchCleanup({
        accountId,
        siteId: siteId || undefined,
        retentionDays: retentionDays ? Number(retentionDays) : undefined,
        olderThan: normalizedOlderThan,
        limit: limit ? Number(limit) : undefined,
        dryRun: executeDryRun,
      });
      setResult(response);
      toast.success(executeDryRun ? 'Dry run completed' : 'Cleanup completed');
    } catch (error: any) {
      toast.error(error?.response?.data?.message || 'Cleanup failed');
    } finally {
      setIsRunning(false);
    }
  };

  return (
    <Card>
      <CardContent className="p-6 space-y-4">
        <div>
          <h3 className="text-lg font-semibold text-gray-900">Batch Cleanup</h3>
          <p className="text-sm text-muted-foreground">
            Run retention cleanup for this user’s sites. Use dry run to preview candidates.
          </p>
        </div>

        <div className="grid gap-4 md:grid-cols-2">
          <div className="space-y-2">
            <Label htmlFor="cleanup-site">Site</Label>
            <select
              id="cleanup-site"
              className="h-9 w-full rounded border border-gray-200 px-3 text-sm"
              value={siteId}
              onChange={(event) => setSiteId(event.target.value)}
              disabled={isRunning}
            >
              <option value="">All sites</option>
              {siteOptions.map((site) => (
                <option key={site.id} value={site.id}>
                  {site.label}
                </option>
              ))}
            </select>
          </div>

          <div className="space-y-2">
            <Label htmlFor="cleanup-retention">Retention Days (override)</Label>
            <Input
              id="cleanup-retention"
              type="number"
              min={1}
              max={3650}
              placeholder="Use site default"
              value={retentionDays}
              onChange={(event) => setRetentionDays(event.target.value)}
              disabled={isRunning}
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor="cleanup-older-than">Older Than (override)</Label>
            <Input
              id="cleanup-older-than"
              type="datetime-local"
              value={olderThan}
              onChange={(event) => setOlderThan(event.target.value)}
              disabled={isRunning}
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor="cleanup-limit">Limit</Label>
            <Input
              id="cleanup-limit"
              type="number"
              min={1}
              max={10000}
              value={limit}
              onChange={(event) => setLimit(event.target.value)}
              disabled={isRunning}
            />
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <Button
            variant="outline"
            onClick={() => runCleanup(true)}
            disabled={isRunning}
          >
            Run Dry Run
          </Button>
          <Button
            variant="destructive"
            onClick={() => runCleanup(false)}
            disabled={isRunning}
          >
            Run Cleanup
          </Button>
        </div>

        {result && (
          <div className="rounded border border-gray-200 bg-gray-50 p-4 text-sm text-gray-700">
            <div>Candidates: {result.candidates}</div>
            <div>Deleted batches: {result.deletedBatches}</div>
            <div>Deleted files: {result.deletedFiles}</div>
            <div>Deleted bytes: {result.deletedBytes.toLocaleString()}</div>
            {result.errors.length > 0 && (
              <div className="mt-2 text-red-600">
                Errors: {result.errors.join(' | ')}
              </div>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

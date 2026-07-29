/**
 * AdminSettingsPage - Admin view for runtime-configurable system settings.
 *
 * Route: /admin/settings
 * Requires: ROLE_ADMIN (backend enforced)
 */

import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Header } from '@/widgets/header/Header';
import { PageHeader } from '@/shared/ui/page-header';
import { Button } from '@/shared/ui/ui/button';
import { Card, CardContent } from '@/shared/ui/ui/card';
import { Label } from '@/shared/ui/ui/label';
import { Input } from '@/shared/ui/ui/input';
import { toast } from 'sonner';
import { getServerErrorMessage } from '@/shared/api/error-handler';
import { getBatchRetentionSchedule, updateBatchRetentionSchedule } from '@/entities/settings';

const settingsKeys = {
  all: ['settings'] as const,
  retentionSchedule: () => [...settingsKeys.all, 'batchRetentionSchedule'] as const,
};

export default function AdminSettingsPage() {
  const queryClient = useQueryClient();

  const scheduleQuery = useQuery({
    queryKey: settingsKeys.retentionSchedule(),
    queryFn: getBatchRetentionSchedule,
    staleTime: 60000, // settings change infrequently
    gcTime: 300000,
  });

  const updateMutation = useMutation({
    mutationFn: (cron: string) => updateBatchRetentionSchedule(cron),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: settingsKeys.retentionSchedule() });
      toast.success('Schedule updated');
    },
    onError: (error: unknown) => {
      toast.error(getServerErrorMessage(error) ?? 'Failed to update schedule');
    },
  });

  const [cron, setCron] = useState<string>('');

  // Seed (and re-seed) the editor from the loaded schedule. Adjusting during
  // render instead of in an effect keeps the field from flashing the stale value.
  const loadedCron = scheduleQuery.data?.cron;
  const [seededCron, setSeededCron] = useState<string | undefined>(undefined);
  if (loadedCron && loadedCron !== seededCron) {
    setSeededCron(loadedCron);
    setCron(loadedCron);
  }

  const metaText = useMemo(() => {
    if (!scheduleQuery.data) return '';
    const updatedAt = scheduleQuery.data.updatedAt ? new Date(scheduleQuery.data.updatedAt).toLocaleString() : null;
    return scheduleQuery.data.source === 'DB'
      ? `Source: DB${updatedAt ? ` (updated ${updatedAt})` : ''}`
      : 'Source: CONFIG (env/default)';
  }, [scheduleQuery.data]);

  const save = async () => {
    const trimmed = cron.trim();
    if (!trimmed) {
      toast.error('Cron cannot be empty');
      return;
    }
    await updateMutation.mutateAsync(trimmed);
  };

  return (
    <div className="min-h-screen bg-white">
      <Header />

      <main className="mx-auto max-w-[1120px] space-y-6 px-6 py-6">
        <PageHeader
          title="Admin Settings"
          subtitle="Runtime-configurable system settings (admin only)."
        />

        <Card>
          <CardContent className="p-6 space-y-4">
            <div>
              <h3 className="text-[15px] font-medium tracking-[-0.24px] text-ink-title">Retention Cleanup Schedule</h3>
              <p className="text-sm text-muted-foreground">
                Controls when old batches/uploads are deleted automatically. Cron format: sec min hour day month day-of-week.
              </p>
              {metaText && (
                <p className="mt-2 text-xs text-ink-muted">{metaText}</p>
              )}
            </div>

            <div className="grid gap-4 md:grid-cols-2">
              <div className="space-y-2 md:col-span-2">
                <Label htmlFor="retention-cron">Cron</Label>
                <Input
                  id="retention-cron"
                  placeholder="0 0 2 * * *"
                  value={cron}
                  onChange={(e) => setCron(e.target.value)}
                  disabled={scheduleQuery.isLoading || updateMutation.isPending}
                />
                <div className="text-xs text-ink-muted">
                  Examples: daily 02:00 = <code className="font-mono">0 0 2 * * *</code>, every 6 hours =
                  <code className="ml-1 font-mono">0 0 */6 * * *</code>
                </div>
              </div>
            </div>

            <div className="flex flex-wrap items-center gap-2">
              <Button
                variant="outline"
                onClick={() => scheduleQuery.refetch()}
                disabled={updateMutation.isPending}
              >
                Refresh
              </Button>
              <Button
                onClick={save}
                disabled={scheduleQuery.isLoading || updateMutation.isPending}
              >
                {updateMutation.isPending ? 'Saving...' : 'Save'}
              </Button>
            </div>

            {scheduleQuery.isError && (
              <div className="rounded-lg border border-danger-border bg-danger-bg p-3 text-sm text-danger-text">
                Failed to load schedule.
              </div>
            )}
          </CardContent>
        </Card>
      </main>
    </div>
  );
}

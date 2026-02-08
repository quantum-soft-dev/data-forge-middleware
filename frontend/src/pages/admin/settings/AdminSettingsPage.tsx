/**
 * AdminSettingsPage - Admin view for runtime-configurable system settings.
 *
 * Route: /admin/settings
 * Requires: ROLE_ADMIN (backend enforced)
 */

import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Header } from '@/widgets/header/Header';
import { Button } from '@/shared/ui/ui/button';
import { Card, CardContent } from '@/shared/ui/ui/card';
import { Label } from '@/shared/ui/ui/label';
import { Input } from '@/shared/ui/ui/input';
import { toast } from 'sonner';
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
    onError: (error: any) => {
      toast.error(error?.response?.data?.message || 'Failed to update schedule');
    },
  });

  const [cron, setCron] = useState<string>('');

  useEffect(() => {
    if (scheduleQuery.data?.cron) setCron(scheduleQuery.data.cron);
  }, [scheduleQuery.data?.cron]);

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
    <div className="min-h-screen bg-gray-50">
      <Header />

      <main className="mx-auto max-w-5xl px-4 py-8 sm:px-6 lg:px-8 space-y-6">
        <div>
          <h2 className="text-2xl font-semibold text-gray-900">Admin Settings</h2>
          <p className="text-sm text-gray-600 mt-1">
            Runtime-configurable system settings (admin only).
          </p>
        </div>

        <Card>
          <CardContent className="p-6 space-y-4">
            <div>
              <h3 className="text-lg font-semibold text-gray-900">Retention Cleanup Schedule</h3>
              <p className="text-sm text-muted-foreground">
                Controls when old batches/uploads are deleted automatically. Cron format: sec min hour day month day-of-week.
              </p>
              {metaText && (
                <p className="mt-2 text-xs text-gray-500">{metaText}</p>
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
                <div className="text-xs text-gray-500">
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
              <div className="rounded border border-red-200 bg-red-50 p-3 text-sm text-red-800">
                Failed to load schedule.
              </div>
            )}
          </CardContent>
        </Card>
      </main>
    </div>
  );
}

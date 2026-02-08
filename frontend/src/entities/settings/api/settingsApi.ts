import { apiClient } from '@/shared/api/client';
import { SETTINGS_BATCH_RETENTION_SCHEDULE } from '@/shared/api/apiRoutes';
import type { BatchRetentionSchedule } from '../model/types';

export async function getBatchRetentionSchedule(): Promise<BatchRetentionSchedule> {
  const response = await apiClient.get<BatchRetentionSchedule>(SETTINGS_BATCH_RETENTION_SCHEDULE);
  return response.data;
}

export async function updateBatchRetentionSchedule(cron: string): Promise<BatchRetentionSchedule> {
  const response = await apiClient.put<BatchRetentionSchedule>(SETTINGS_BATCH_RETENTION_SCHEDULE, { cron });
  return response.data;
}


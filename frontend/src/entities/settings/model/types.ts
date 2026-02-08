export type BatchRetentionScheduleSource = 'DB' | 'CONFIG';

export interface BatchRetentionSchedule {
  cron: string;
  source: BatchRetentionScheduleSource;
  updatedAt: string | null;
}


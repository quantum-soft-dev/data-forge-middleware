package com.bitbi.dfm.batch.application;

/**
 * Published when the batch retention cleanup cron schedule is updated.
 */
public record BatchRetentionScheduleChangedEvent(String cron) {
}


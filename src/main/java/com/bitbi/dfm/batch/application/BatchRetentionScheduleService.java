package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.settings.domain.AppSetting;
import com.bitbi.dfm.settings.domain.AppSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Resolves and persists runtime-configurable scheduling settings for batch retention cleanup.
 */
@Service
public class BatchRetentionScheduleService {

    private static final Logger logger = LoggerFactory.getLogger(BatchRetentionScheduleService.class);

    public static final String CRON_SETTING_KEY = "batch.retention.cron";

    private final AppSettingRepository appSettingRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final String fallbackCron;

    public BatchRetentionScheduleService(
            AppSettingRepository appSettingRepository,
            ApplicationEventPublisher eventPublisher,
            @Value("${batch.retention.cron:0 0 2 * * *}") String fallbackCron) {
        this.appSettingRepository = appSettingRepository;
        this.eventPublisher = eventPublisher;
        this.fallbackCron = fallbackCron;
    }

    public BatchRetentionSchedule getSchedule() {
        Optional<AppSetting> stored = appSettingRepository.findById(CRON_SETTING_KEY);
        if (stored.isPresent()) {
            String value = stored.get().getValue();
            if (isValidCron(value)) {
                return new BatchRetentionSchedule(value, BatchRetentionScheduleSource.DB, stored.get().getUpdatedAt());
            }
            logger.warn("Invalid cron expression stored for {}: '{}'. Falling back to configured default.", CRON_SETTING_KEY, value);
        }
        return new BatchRetentionSchedule(fallbackCron, BatchRetentionScheduleSource.CONFIG, null);
    }

    public String getEffectiveCron() {
        return getSchedule().cron();
    }

    @Transactional
    public BatchRetentionSchedule updateCron(String cron) {
        if (cron == null || cron.isBlank()) {
            throw new IllegalArgumentException("cron cannot be blank");
        }
        validateCronOrThrow(cron);

        AppSetting setting = appSettingRepository.findById(CRON_SETTING_KEY)
                .orElseGet(() -> new AppSetting(CRON_SETTING_KEY, cron));
        setting.setValue(cron);
        AppSetting saved = appSettingRepository.save(setting);

        // Notify scheduler to reschedule without requiring restart.
        eventPublisher.publishEvent(new BatchRetentionScheduleChangedEvent(saved.getValue()));

        return new BatchRetentionSchedule(saved.getValue(), BatchRetentionScheduleSource.DB, saved.getUpdatedAt());
    }

    private boolean isValidCron(String cron) {
        try {
            CronExpression.parse(cron);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void validateCronOrThrow(String cron) {
        try {
            CronExpression.parse(cron);
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : "invalid format";
            throw new IllegalArgumentException(
                    "Invalid cron expression: " + message +
                            ". Expected format: sec min hour day month day-of-week (e.g., '0 0 2 * * *')."
            );
        }
    }

    public enum BatchRetentionScheduleSource {
        DB,
        CONFIG
    }

    public record BatchRetentionSchedule(String cron, BatchRetentionScheduleSource source, Instant updatedAt) {}
}

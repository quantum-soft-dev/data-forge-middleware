package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.settings.domain.AppSetting;
import com.bitbi.dfm.settings.domain.AppSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private final String fallbackCron;

    public BatchRetentionScheduleService(
            AppSettingRepository appSettingRepository,
            @Value("${batch.retention.cron:0 0 2 * * *}") String fallbackCron) {
        this.appSettingRepository = appSettingRepository;
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
        if (!isValidCron(cron)) {
            throw new IllegalArgumentException("Invalid cron expression: " + cron);
        }

        AppSetting setting = appSettingRepository.findById(CRON_SETTING_KEY)
                .orElseGet(() -> new AppSetting(CRON_SETTING_KEY, cron));
        setting.setValue(cron);
        AppSetting saved = appSettingRepository.save(setting);

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

    public enum BatchRetentionScheduleSource {
        DB,
        CONFIG
    }

    public record BatchRetentionSchedule(String cron, BatchRetentionScheduleSource source, Instant updatedAt) {}
}


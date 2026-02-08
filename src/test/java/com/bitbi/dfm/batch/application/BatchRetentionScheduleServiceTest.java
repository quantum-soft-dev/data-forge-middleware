package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.settings.domain.AppSetting;
import com.bitbi.dfm.settings.domain.AppSettingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BatchRetentionScheduleService")
class BatchRetentionScheduleServiceTest {

    @Test
    @DisplayName("getSchedule should fallback to config when DB has no value")
    void getSchedule_shouldFallbackToConfig() {
        InMemoryAppSettingRepository repo = new InMemoryAppSettingRepository();
        CapturingEventPublisher publisher = new CapturingEventPublisher();
        BatchRetentionScheduleService service = new BatchRetentionScheduleService(repo, publisher, "0 0 2 * * *");

        var schedule = service.getSchedule();

        assertThat(schedule.cron()).isEqualTo("0 0 2 * * *");
        assertThat(schedule.source()).isEqualTo(BatchRetentionScheduleService.BatchRetentionScheduleSource.CONFIG);
        assertThat(schedule.updatedAt()).isNull();
    }

    @Test
    @DisplayName("updateCron should persist value and publish schedule changed event")
    void updateCron_shouldPersistAndPublish() {
        InMemoryAppSettingRepository repo = new InMemoryAppSettingRepository();
        CapturingEventPublisher publisher = new CapturingEventPublisher();
        BatchRetentionScheduleService service = new BatchRetentionScheduleService(repo, publisher, "0 0 2 * * *");

        var updated = service.updateCron("0 0 3 * * *");

        assertThat(updated.cron()).isEqualTo("0 0 3 * * *");
        assertThat(updated.source()).isEqualTo(BatchRetentionScheduleService.BatchRetentionScheduleSource.DB);
        assertThat(updated.updatedAt()).isNotNull();

        assertThat(repo.findById(BatchRetentionScheduleService.CRON_SETTING_KEY))
                .isPresent()
                .get()
                .extracting(AppSetting::getValue)
                .isEqualTo("0 0 3 * * *");

        assertThat(publisher.events.stream()
                .filter(e -> e instanceof BatchRetentionScheduleChangedEvent)
                .map(e -> ((BatchRetentionScheduleChangedEvent) e).cron())
                .toList()).contains("0 0 3 * * *");
    }

    @Test
    @DisplayName("updateCron should reject invalid cron expressions with helpful message")
    void updateCron_shouldRejectInvalidCron() {
        InMemoryAppSettingRepository repo = new InMemoryAppSettingRepository();
        CapturingEventPublisher publisher = new CapturingEventPublisher();
        BatchRetentionScheduleService service = new BatchRetentionScheduleService(repo, publisher, "0 0 2 * * *");

        assertThatThrownBy(() -> service.updateCron("not-a-cron"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected format");
    }

    @Test
    @DisplayName("getSchedule should fallback to config when DB contains invalid value")
    void getSchedule_shouldFallbackWhenDbInvalid() {
        InMemoryAppSettingRepository repo = new InMemoryAppSettingRepository();
        CapturingEventPublisher publisher = new CapturingEventPublisher();
        BatchRetentionScheduleService service = new BatchRetentionScheduleService(repo, publisher, "0 0 2 * * *");

        repo.save(new AppSetting(BatchRetentionScheduleService.CRON_SETTING_KEY, "bad bad bad"));

        var schedule = service.getSchedule();
        assertThat(schedule.source()).isEqualTo(BatchRetentionScheduleService.BatchRetentionScheduleSource.CONFIG);
        assertThat(schedule.cron()).isEqualTo("0 0 2 * * *");
    }

    private static class InMemoryAppSettingRepository implements AppSettingRepository {
        private final Map<String, AppSetting> data = new ConcurrentHashMap<>();

        @Override
        public Optional<AppSetting> findById(String key) {
            return Optional.ofNullable(data.get(key));
        }

        @Override
        public AppSetting save(AppSetting setting) {
            data.put(setting.getKey(), setting);
            return setting;
        }
    }

    private static class CapturingEventPublisher implements ApplicationEventPublisher {
        private final List<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }

        @Override
        public void publishEvent(ApplicationEvent event) {
            events.add(event);
        }
    }
}

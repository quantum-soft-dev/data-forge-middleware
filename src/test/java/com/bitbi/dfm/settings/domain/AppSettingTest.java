package com.bitbi.dfm.settings.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AppSetting")
class AppSettingTest {

    @Test
    @DisplayName("constructor should set createdAt and updatedAt")
    void constructor_shouldSetTimestamps() {
        AppSetting setting = new AppSetting("k", "v");

        assertThat(setting.getCreatedAt()).isNotNull();
        assertThat(setting.getUpdatedAt()).isNotNull();
        assertThat(setting.getUpdatedAt()).isEqualTo(setting.getCreatedAt());
    }

    @Test
    @DisplayName("setValue should update updatedAt")
    void setValue_shouldUpdateTimestamp() {
        AppSetting setting = new AppSetting("k", "v1");
        var before = setting.getUpdatedAt();

        setting.setValue("v2");

        assertThat(setting.getValue()).isEqualTo("v2");
        assertThat(setting.getUpdatedAt()).isNotNull();
        assertThat(setting.getUpdatedAt()).isAfterOrEqualTo(before);
    }
}


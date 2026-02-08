package com.bitbi.dfm.settings.domain;

import java.util.Optional;

/**
 * Repository interface for application settings.
 */
public interface AppSettingRepository {

    Optional<AppSetting> findById(String key);

    AppSetting save(AppSetting setting);
}


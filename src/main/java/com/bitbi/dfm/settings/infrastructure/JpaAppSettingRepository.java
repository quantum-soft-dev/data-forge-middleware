package com.bitbi.dfm.settings.infrastructure;

import com.bitbi.dfm.settings.domain.AppSetting;
import com.bitbi.dfm.settings.domain.AppSettingRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA implementation of AppSettingRepository.
 */
@Repository
public interface JpaAppSettingRepository extends JpaRepository<AppSetting, String>, AppSettingRepository {
}


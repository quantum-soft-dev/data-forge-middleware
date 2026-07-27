package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.DownloadLinkRepository;
import com.bitbi.dfm.plugin.infrastructure.ParquetExportProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DownloadLinkPurgeScheduler} (028-parquet-export-plugin, T013).
 */
@ExtendWith(MockitoExtension.class)
class DownloadLinkPurgeSchedulerTest {

    @Mock
    private DownloadLinkRepository downloadLinkRepository;

    @Test
    @DisplayName("Should purge with cutoff = now minus configured retention")
    void shouldPurgeWithConfiguredRetention() {
        ParquetExportProperties properties = new ParquetExportProperties();
        properties.setPurgeRetentionDays(7);
        DownloadLinkPurgeScheduler scheduler =
                new DownloadLinkPurgeScheduler(downloadLinkRepository, properties);
        when(downloadLinkRepository.purge(any())).thenReturn(3);

        scheduler.purgeStaleLinks();

        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(downloadLinkRepository).purge(cutoffCaptor.capture());
        LocalDateTime expected = LocalDateTime.now(ZoneOffset.UTC).minusDays(7);
        long driftSeconds = Math.abs(ChronoUnit.SECONDS.between(expected, cutoffCaptor.getValue()));
        assertTrue(driftSeconds < 60, "cutoff must be ~now-7d, drift was " + driftSeconds + "s");
    }
}

package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.batch.infrastructure.S3PresignedUrlService;
import com.bitbi.dfm.plugin.application.ParquetExportFileService.FileType;
import com.bitbi.dfm.plugin.application.ParquetExportFileService.ParquetFileItem;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.DownloadLink;
import com.bitbi.dfm.plugin.domain.DownloadLinkRepository;
import com.bitbi.dfm.plugin.infrastructure.ParquetExportProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DownloadLinkService} (028-parquet-export-plugin, T010).
 */
@ExtendWith(MockitoExtension.class)
class DownloadLinkServiceTest {

    private static final Long ACCOUNT_PLUGIN_ID = 42L;
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID SITE_ID = UUID.randomUUID();

    @Mock
    private DownloadLinkRepository downloadLinkRepository;

    @Mock
    private AccountPluginRepository accountPluginRepository;

    @Mock
    private S3PresignedUrlService presignedUrlService;

    @Mock
    private PluginAuditService pluginAuditService;

    private ParquetExportProperties properties;
    private io.micrometer.core.instrument.simple.SimpleMeterRegistry meterRegistry;
    private DownloadLinkService service;

    @BeforeEach
    void setUp() {
        properties = new ParquetExportProperties();
        meterRegistry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        service = new DownloadLinkService(downloadLinkRepository, accountPluginRepository,
                presignedUrlService, pluginAuditService, properties, meterRegistry);
        lenient().when(downloadLinkRepository.save(any(DownloadLink.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private ParquetFileItem fileItem(String table) {
        return new ParquetFileItem(SITE_ID, "shop.example.com", table, FileType.DELTA,
                1L, 2L, null, LocalDateTime.of(2026, 7, 20, 10, 0),
                table + "_seq1-2.parquet", "egress/" + SITE_ID + "/" + table + "/delta/seq=1-2.parquet",
                null, null);
    }

    private ParquetFileItem abandonedBatchItem() {
        return new ParquetFileItem(SITE_ID, "shop.example.com", "orders", FileType.BATCH,
                1L, 2L, null, LocalDateTime.of(2026, 7, 20, 10, 0),
                "orders_batch" + UUID.randomUUID() + ".parquet", null,
                UUID.randomUUID(), "abandoned");
    }

    @Nested
    @DisplayName("registerLinks")
    class RegisterLinks {

        @Test
        @DisplayName("Should register one link per file with configured TTL")
        void shouldRegisterLinkPerFile() {
            List<DownloadLink> links = service.registerLinks(ACCOUNT_PLUGIN_ID,
                    List.of(fileItem("orders"), fileItem("items")));

            assertEquals(2, links.size());
            verify(downloadLinkRepository, times(2)).save(any(DownloadLink.class));
            DownloadLink first = links.get(0);
            assertEquals(ACCOUNT_PLUGIN_ID, first.getAccountPluginId());
            assertEquals("orders_seq1-2.parquet", first.getFileName());
            assertTrue(first.getS3Key().contains("orders"));
            long ttlSeconds = ChronoUnit.SECONDS.between(first.getCreatedAt(), first.getExpiresAt());
            assertEquals(properties.getLinkTtlSeconds(), ttlSeconds);
        }

        @Test
        @DisplayName("Should skip abandoned files that have no S3 key")
        void shouldSkipAbandonedFilesWithoutS3Key() {
            List<DownloadLink> links = service.registerLinks(ACCOUNT_PLUGIN_ID,
                    List.of(fileItem("orders"), abandonedBatchItem()));

            assertEquals(2, links.size());
            assertNotNull(links.get(0));
            assertNull(links.get(1));
            verify(downloadLinkRepository, times(1)).save(any(DownloadLink.class));
        }
    }

    @Nested
    @DisplayName("consume")
    class Consume {

        private DownloadLink storedLink() {
            return DownloadLink.register(ACCOUNT_PLUGIN_ID, "egress/key.parquet", "file.parquet",
                    Duration.ofHours(1));
        }

        @Test
        @DisplayName("Should presign with configured short TTL when consume wins")
        void shouldPresignOnSuccessfulConsume() {
            DownloadLink link = storedLink();
            AccountPlugin accountPlugin = mock(AccountPlugin.class);
            when(accountPlugin.getAccountId()).thenReturn(ACCOUNT_ID);
            when(downloadLinkRepository.consume(eq(link.getToken()), any())).thenReturn(1);
            when(downloadLinkRepository.findByToken(link.getToken())).thenReturn(Optional.of(link));
            when(accountPluginRepository.findById(ACCOUNT_PLUGIN_ID)).thenReturn(Optional.of(accountPlugin));
            when(presignedUrlService.generatePresignedUrl(eq("egress/key.parquet"), eq("file.parquet"),
                    eq(Duration.ofSeconds(properties.getPresignTtlSeconds()))))
                    .thenReturn(new S3PresignedUrlService.PresignedUrlResult("https://s3/presigned", Instant.now()));

            String url = service.consume(link.getToken());

            assertEquals("https://s3/presigned", url);
            verify(pluginAuditService).logLinkConsumed("parquet-export", ACCOUNT_ID,
                    "file.parquet", "egress/key.parquet");
        }

        @Test
        @DisplayName("Should throw LinkGoneException when link exists but consume lost")
        void shouldThrowGoneWhenAlreadyConsumed() {
            DownloadLink link = storedLink();
            when(downloadLinkRepository.consume(eq(link.getToken()), any())).thenReturn(0);
            when(downloadLinkRepository.findByToken(link.getToken())).thenReturn(Optional.of(link));

            assertThrows(DownloadLinkService.LinkGoneException.class, () -> service.consume(link.getToken()));

            verify(presignedUrlService, never()).generatePresignedUrl(any(), any(), any());
            verify(pluginAuditService).logLinkRejected(eq("parquet-export"), any(), any());
        }

        @Test
        @DisplayName("Should throw LinkNotFoundException for unknown token without touching the audit log")
        void shouldThrowNotFoundForUnknownToken() {
            when(downloadLinkRepository.consume(eq("unknown"), any())).thenReturn(0);
            when(downloadLinkRepository.findByToken("unknown")).thenReturn(Optional.empty());

            assertThrows(DownloadLinkService.LinkNotFoundException.class, () -> service.consume("unknown"));

            verify(presignedUrlService, never()).generatePresignedUrl(any(), any(), any());
            // The route is anonymous: auditing unknown tokens would let garbage requests flood
            // the partitioned audit table (write amplification) — a Micrometer counter instead.
            verifyNoInteractions(pluginAuditService);
            assertEquals(1.0, meterRegistry.counter("plugin.parquet.export.download.rejected",
                    "reason", "unknown").count());
        }
    }

    @Nested
    @DisplayName("configuration defaults")
    class ConfigurationDefaults {

        @Test
        @DisplayName("Should default to 1h link TTL, 60s presign TTL, 7d retention")
        void shouldHaveDocumentedDefaults() {
            assertEquals(3600, properties.getLinkTtlSeconds());
            assertEquals(60, properties.getPresignTtlSeconds());
            assertEquals(7, properties.getPurgeRetentionDays());
            assertEquals("", properties.getBaseUrl());
        }

        @Test
        @DisplayName("Should reject non-positive durations via bean validation")
        void shouldRejectNonPositiveValues() {
            try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
                jakarta.validation.Validator validator = factory.getValidator();
                assertTrue(validator.validate(properties).isEmpty(), "defaults must be valid");

                ParquetExportProperties broken = new ParquetExportProperties();
                broken.setLinkTtlSeconds(0);
                broken.setPresignTtlSeconds(-1);
                broken.setPurgeRetentionDays(0);
                broken.setPurgeIntervalMs(0);
                assertEquals(4, validator.validate(broken).size());
            }
        }
    }
}

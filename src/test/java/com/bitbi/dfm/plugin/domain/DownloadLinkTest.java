package com.bitbi.dfm.plugin.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DownloadLink} (028-parquet-export-plugin, T001).
 */
class DownloadLinkTest {

    private static final Duration TTL = Duration.ofHours(1);

    @Test
    @DisplayName("Should populate all fields when registering link")
    void shouldPopulateAllFieldsWhenRegisteringLink() {
        DownloadLink link = DownloadLink.register(42L, "egress/site/orders/delta/seq=1-2.parquet",
                "orders_seq1-2.parquet", TTL);

        assertNotNull(link.getId());
        assertEquals(42L, link.getAccountPluginId());
        assertEquals("egress/site/orders/delta/seq=1-2.parquet", link.getS3Key());
        assertEquals("orders_seq1-2.parquet", link.getFileName());
        assertNotNull(link.getCreatedAt());
        assertNull(link.getConsumedAt());
    }

    @Test
    @DisplayName("Should generate 43-char URL-safe token")
    void shouldGenerateUrlSafeToken() {
        DownloadLink link = DownloadLink.register(1L, "key", "file.parquet", TTL);

        assertNotNull(link.getToken());
        assertEquals(43, link.getToken().length(), "32 random bytes base64-url without padding = 43 chars");
        assertTrue(link.getToken().matches("^[A-Za-z0-9_-]{43}$"), "token must be URL-safe: " + link.getToken());
    }

    @Test
    @DisplayName("Should set expiresAt to createdAt plus TTL")
    void shouldSetExpiresAtFromTtl() {
        DownloadLink link = DownloadLink.register(1L, "key", "file.parquet", TTL);

        long deltaSeconds = ChronoUnit.SECONDS.between(link.getCreatedAt().plus(TTL), link.getExpiresAt());
        assertEquals(0, deltaSeconds);
        assertTrue(link.getExpiresAt().isAfter(LocalDateTime.now(java.time.ZoneOffset.UTC)));
    }

    @Test
    @DisplayName("Should generate distinct tokens across registrations")
    void shouldGenerateDistinctTokens() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            tokens.add(DownloadLink.register(1L, "key", "file.parquet", TTL).getToken());
        }
        assertEquals(1000, tokens.size());
    }
}

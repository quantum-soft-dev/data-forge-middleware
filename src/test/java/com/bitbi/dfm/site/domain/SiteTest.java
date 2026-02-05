package com.bitbi.dfm.site.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for Site entity.
 *
 * <p>Tests domain logic including:</p>
 * <ul>
 *   <li>getDisplayDomain() extracts display domain from composite domain</li>
 *   <li>Edge cases for domain parsing</li>
 * </ul>
 */
@DisplayName("Site Domain Tests")
class SiteTest {

    @Nested
    @DisplayName("getDisplayDomain() - FR-019 Composite Domain Handling")
    class GetDisplayDomain {

        @Test
        @DisplayName("Should strip UUID prefix from composite domain")
        void shouldStripUuidPrefixFromCompositeDomain() {
            // Given - composite domain "uuid_domain" format
            String compositeDomain = "a1b2c3d4-e5f6-7890-abcd-ef1234567890_example.com";
            Site site = createSiteWithDomain(compositeDomain);

            // When
            String displayDomain = site.getDisplayDomain();

            // Then
            assertThat(displayDomain).isEqualTo("example.com");
        }

        @Test
        @DisplayName("Should handle multi-part domain correctly")
        void shouldHandleMultiPartDomain() {
            // Given - composite domain with subdomain
            String compositeDomain = "c823d8b8-1234-5678-abcd-ef1234567890_app.store.example.com";
            Site site = createSiteWithDomain(compositeDomain);

            // When
            String displayDomain = site.getDisplayDomain();

            // Then
            assertThat(displayDomain).isEqualTo("app.store.example.com");
        }

        @Test
        @DisplayName("Should handle short domain gracefully")
        void shouldHandleShortDomainGracefully() {
            // Given - domain shorter than prefix length (37 chars)
            String shortDomain = "short";
            Site site = createSiteWithDomain(shortDomain);

            // When
            String displayDomain = site.getDisplayDomain();

            // Then - returns original domain
            assertThat(displayDomain).isEqualTo("short");
        }

        @Test
        @DisplayName("Should handle domain exactly at prefix length")
        void shouldHandleDomainExactlyAtPrefixLength() {
            // Given - domain exactly 37 chars (prefix length)
            String domain = "a1b2c3d4-e5f6-7890-abcd-ef1234567890_"; // 37 chars
            Site site = createSiteWithDomain(domain);

            // When
            String displayDomain = site.getDisplayDomain();

            // Then - returns original domain (since length <= 37)
            assertThat(displayDomain).isEqualTo(domain);
        }

        @Test
        @DisplayName("Should handle null domain gracefully")
        void shouldHandleNullDomainGracefully() {
            // Given - site with null domain (edge case)
            Site site = createSiteWithDomain(null);

            // When
            String displayDomain = site.getDisplayDomain();

            // Then
            assertThat(displayDomain).isNull();
        }

        @Test
        @DisplayName("Should handle domain with underscore in name after prefix")
        void shouldHandleDomainWithUnderscoreInName() {
            // Given - composite domain where actual domain contains underscore
            String compositeDomain = "c823d8b8-1234-5678-abcd-ef1234567890_store_01.example.com";
            Site site = createSiteWithDomain(compositeDomain);

            // When
            String displayDomain = site.getDisplayDomain();

            // Then
            assertThat(displayDomain).isEqualTo("store_01.example.com");
        }

        @Test
        @DisplayName("Should handle real-world example: roshel domain")
        void shouldHandleRealWorldRoshelDomain() {
            // Given - real example from bug report
            String compositeDomain = "c823d8b8-abcd-efgh-1234-567890abcdef_roshel";
            Site site = createSiteWithDomain(compositeDomain);

            // When
            String displayDomain = site.getDisplayDomain();

            // Then
            assertThat(displayDomain).isEqualTo("roshel");
        }
    }

    /**
     * Helper method to create a Site with a specific domain for testing.
     * Uses reflection to set the domain field since Site.create() validates domain format.
     */
    private Site createSiteWithDomain(String domain) {
        UUID id = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        // Use reflection to create Site with specific domain
        try {
            java.lang.reflect.Constructor<Site> constructor = Site.class.getDeclaredConstructor(
                    UUID.class, UUID.class, String.class, String.class,
                    String.class, Boolean.class, Integer.class, LocalDateTime.class, LocalDateTime.class
            );
            constructor.setAccessible(true);
            return constructor.newInstance(
                    id, accountId, domain, "$2a$10$dummyHash",
                    "Test Site", true, 45, now, now
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to create Site for testing", e);
        }
    }
}

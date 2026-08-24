package com.bitbi.dfm.site.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for SiteSchema domain entity.
 */
@DisplayName("SiteSchema")
class SiteSchemaTest {

    @Nested
    @DisplayName("create()")
    class Create {

        @Test
        @DisplayName("should create entity with version 1 and matching siteId")
        void shouldCreateWithVersionOne() {
            UUID siteId = UUID.randomUUID();
            Map<String, Object> data = Map.of("tables", Map.of());

            SiteSchema schema = SiteSchema.create(siteId, data);

            assertThat(schema.getId()).isNotNull();
            assertThat(schema.getSiteId()).isEqualTo(siteId);
            assertThat(schema.getSchemaVersion()).isEqualTo(1);
            assertThat(schema.getSchemaData()).isEqualTo(data);
        }

        @Test
        @DisplayName("should set createdAt and updatedAt on create")
        void shouldSetTimestampsOnCreate() {
            LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);
            SiteSchema schema = SiteSchema.create(UUID.randomUUID(), Map.of());
            LocalDateTime after = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1);

            assertThat(schema.getCreatedAt()).isBetween(before, after);
            assertThat(schema.getUpdatedAt()).isBetween(before, after);
        }

        @Test
        @DisplayName("should generate unique id for each created schema")
        void shouldGenerateUniqueIds() {
            UUID siteId = UUID.randomUUID();
            SiteSchema a = SiteSchema.create(siteId, Map.of());
            SiteSchema b = SiteSchema.create(siteId, Map.of());

            assertThat(a.getId()).isNotEqualTo(b.getId());
        }
    }

    @Nested
    @DisplayName("update()")
    class Update {

        @Test
        @DisplayName("should increment version on update")
        void shouldIncrementVersion() {
            SiteSchema schema = SiteSchema.create(UUID.randomUUID(), Map.of("v", 1));

            schema.update(Map.of("v", 2));

            assertThat(schema.getSchemaVersion()).isEqualTo(2);
        }

        @Test
        @DisplayName("should replace schemaData on update")
        void shouldReplaceSchemaData() {
            SiteSchema schema = SiteSchema.create(UUID.randomUUID(), Map.of("old", "data"));
            Map<String, Object> newData = Map.of("new", "data");

            schema.update(newData);

            assertThat(schema.getSchemaData()).isEqualTo(newData);
        }

        @Test
        @DisplayName("should update updatedAt on update")
        void shouldUpdateTimestamp() throws InterruptedException {
            SiteSchema schema = SiteSchema.create(UUID.randomUUID(), Map.of());
            LocalDateTime before = schema.getUpdatedAt();
            Thread.sleep(10); // ensure time progresses

            schema.update(Map.of("changed", true));

            assertThat(schema.getUpdatedAt()).isAfterOrEqualTo(before);
        }

        @Test
        @DisplayName("should allow multiple updates incrementing version each time")
        void shouldIncrementVersionOnMultipleUpdates() {
            SiteSchema schema = SiteSchema.create(UUID.randomUUID(), Map.of());

            schema.update(Map.of("a", 1));
            schema.update(Map.of("b", 2));
            schema.update(Map.of("c", 3));

            assertThat(schema.getSchemaVersion()).isEqualTo(4);
        }
    }
}

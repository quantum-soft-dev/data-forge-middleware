package com.bitbi.dfm.plugin.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for PluginAuditLog entity.
 */
@DisplayName("PluginAuditLog")
class PluginAuditLogTest {

    private static final String PLUGIN_ID = "bit-bi";
    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    @Nested
    @DisplayName("Metadata Field")
    class MetadataField {

        @Test
        @DisplayName("Should store metadata as Map for SQL generation events")
        void shouldStoreMetadataAsMap() {
            // Given
            Map<String, Object> metadata = Map.of(
                    "batchId", UUID.randomUUID().toString(),
                    "siteId", UUID.randomUUID().toString(),
                    "insertCount", 10,
                    "updateCount", 5,
                    "deleteCount", 2,
                    "filesProcessed", 3
            );

            // When
            PluginAuditLog log = PluginAuditLog.success(PLUGIN_ID, ACCOUNT_ID,
                            PluginActionType.SQL_GENERATION_COMPLETED)
                    .withMetadata(metadata);

            // Then
            assertThat(log.getMetadata()).isNotNull();
            assertThat(log.getMetadata()).containsEntry("insertCount", 10);
            assertThat(log.getMetadata()).containsEntry("updateCount", 5);
            assertThat(log.getMetadata()).containsEntry("deleteCount", 2);
        }

        @Test
        @DisplayName("Should allow null metadata for events without extra details")
        void shouldAllowNullMetadata() {
            // When
            PluginAuditLog log = PluginAuditLog.success(PLUGIN_ID, ACCOUNT_ID,
                    PluginActionType.ACTIVATE);

            // Then
            assertThat(log.getMetadata()).isNull();
        }

        @Test
        @DisplayName("Should store s3Key in metadata for completed SQL generation")
        void shouldStoreS3KeyInMetadata() {
            // Given
            String s3Key = "plugins/bit-bi/account-123/site-name/2025-01-01T10:00:00.sql";
            Map<String, Object> metadata = Map.of("s3Key", s3Key);

            // When
            PluginAuditLog log = PluginAuditLog.success(PLUGIN_ID, ACCOUNT_ID,
                            PluginActionType.SQL_GENERATION_COMPLETED)
                    .withMetadata(metadata);

            // Then
            assertThat(log.getMetadata()).containsEntry("s3Key", s3Key);
        }
    }

    @Nested
    @DisplayName("Factory Methods")
    class FactoryMethods {

        @Test
        @DisplayName("Should create success log with correct fields")
        void shouldCreateSuccessLog() {
            PluginAuditLog log = PluginAuditLog.success(PLUGIN_ID, ACCOUNT_ID,
                    PluginActionType.SQL_GENERATION_STARTED);

            assertThat(log.getPluginId()).isEqualTo(PLUGIN_ID);
            assertThat(log.getAccountId()).isEqualTo(ACCOUNT_ID);
            assertThat(log.getActionType()).isEqualTo(PluginActionType.SQL_GENERATION_STARTED);
            assertThat(log.isSuccess()).isTrue();
            assertThat(log.getErrorMessage()).isNull();
            assertThat(log.getOccurredAt()).isNotNull();
        }

        @Test
        @DisplayName("Should create failure log with error message")
        void shouldCreateFailureLog() {
            String errorMessage = "Table name contains invalid characters";

            PluginAuditLog log = PluginAuditLog.failure(PLUGIN_ID, ACCOUNT_ID,
                    PluginActionType.SQL_GENERATION_FAILED, errorMessage);

            assertThat(log.isSuccess()).isFalse();
            assertThat(log.getErrorMessage()).isEqualTo(errorMessage);
        }

        @Test
        @DisplayName("Should truncate error message to 500 characters")
        void shouldTruncateErrorMessage() {
            String longMessage = "A".repeat(600);

            PluginAuditLog log = PluginAuditLog.failure(PLUGIN_ID, ACCOUNT_ID,
                    PluginActionType.SQL_GENERATION_FAILED, longMessage);

            assertThat(log.getErrorMessage()).hasSize(500);
        }
    }

    @Nested
    @DisplayName("Builder Methods")
    class BuilderMethods {

        @Test
        @DisplayName("Should chain withMetadata with other builder methods")
        void shouldChainWithMetadataWithOtherBuilderMethods() {
            Map<String, Object> metadata = Map.of("batchId", "test-batch");

            PluginAuditLog log = PluginAuditLog.success(PLUGIN_ID, ACCOUNT_ID,
                            PluginActionType.SQL_GENERATION_COMPLETED)
                    .withDuration(1500)
                    .withMetadata(metadata);

            assertThat(log.getDurationMs()).isEqualTo(1500);
            assertThat(log.getMetadata()).containsEntry("batchId", "test-batch");
        }
    }
}

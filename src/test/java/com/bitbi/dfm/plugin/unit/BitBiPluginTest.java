package com.bitbi.dfm.plugin.unit;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.plugin.application.BitBiPlugin;
import com.bitbi.dfm.plugin.application.SqlGenerationService;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.PluginEvent;
import com.bitbi.dfm.plugin.domain.PluginEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BitBiPlugin implementation.
 *
 * <p>Tests:
 * <ul>
 *   <li>Returns correct plugin ID</li>
 *   <li>Returns correct supported events</li>
 *   <li>Schema validates valid tenantId</li>
 *   <li>onActivate logs activation</li>
 *   <li>execute handles BATCH_COMPLETED event</li>
 * </ul>
 *
 * @see BitBiPlugin
 */
@DisplayName("BitBiPlugin")
class BitBiPluginTest {

    private BitBiPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new BitBiPlugin();
        plugin.setDeltaSqlSweepWorker(
                mock(com.bitbi.dfm.plugin.application.DeltaSqlSweepWorker.class));
    }

    @Nested
    @DisplayName("Plugin Metadata")
    class PluginMetadata {

        @Test
        @DisplayName("should return correct plugin ID")
        void shouldReturnCorrectPluginId() {
            assertThat(plugin.getId()).isEqualTo("bit-bi");
        }

        @Test
        @DisplayName("should return correct plugin name")
        void shouldReturnCorrectPluginName() {
            assertThat(plugin.getName()).isEqualTo("Bit BI");
        }

        @Test
        @DisplayName("should return correct plugin version")
        void shouldReturnCorrectPluginVersion() {
            assertThat(plugin.getVersion()).isEqualTo("1.0.0");
        }

        @Test
        @DisplayName("should have static constants for ID, name, and version")
        void shouldHaveStaticConstantsForIdNameAndVersion() {
            assertThat(BitBiPlugin.PLUGIN_ID).isEqualTo("bit-bi");
            assertThat(BitBiPlugin.PLUGIN_NAME).isEqualTo("Bit BI");
            assertThat(BitBiPlugin.PLUGIN_VERSION).isEqualTo("1.0.0");
        }
    }

    @Nested
    @DisplayName("Supported Events")
    class SupportedEvents {

        @Test
        @DisplayName("should support BATCH_COMPLETED event")
        void shouldSupportBatchCompletedEvent() {
            Set<PluginEventType> events = plugin.getSupportedEvents();

            assertThat(events).contains(PluginEventType.BATCH_COMPLETED);
        }

        @Test
        @DisplayName("should return exactly one supported event")
        void shouldReturnExactlyOneSupportedEvent() {
            Set<PluginEventType> events = plugin.getSupportedEvents();

            assertThat(events).hasSize(1);
        }

        @Test
        @DisplayName("should not support BATCH_FAILED event")
        void shouldNotSupportBatchFailedEvent() {
            Set<PluginEventType> events = plugin.getSupportedEvents();

            assertThat(events).doesNotContain(PluginEventType.BATCH_FAILED);
        }
    }

    @Nested
    @DisplayName("JSON Schema")
    class JsonSchema {

        @Test
        @DisplayName("should return valid JSON Schema string")
        void shouldReturnValidJsonSchemaString() {
            String schema = plugin.getSchemaJson();

            assertThat(schema)
                .isNotNull()
                .isNotEmpty()
                .contains("$schema")
                .contains("http://json-schema.org/draft-07/schema#")
                .contains("tenantId")
                .contains("required");
        }

        @Test
        @DisplayName("schema should require tenantId field")
        void schemaShouldRequireTenantIdField() {
            String schema = plugin.getSchemaJson();

            assertThat(schema).contains("\"required\"");
            assertThat(schema).contains("\"tenantId\"");
        }

        @Test
        @DisplayName("schema should specify tenantId as string type")
        void schemaShouldSpecifyTenantIdAsStringType() {
            String schema = plugin.getSchemaJson();

            assertThat(schema).contains("\"type\": \"string\"");
        }

        @Test
        @DisplayName("schema should specify tenantId length constraints")
        void schemaShouldSpecifyTenantIdLengthConstraints() {
            String schema = plugin.getSchemaJson();

            assertThat(schema).contains("\"minLength\": 1");
            assertThat(schema).contains("\"maxLength\": 64");
        }

        @Test
        @DisplayName("schema should specify tenantId pattern")
        void schemaShouldSpecifyTenantIdPattern() {
            String schema = plugin.getSchemaJson();

            assertThat(schema).contains("\"pattern\"");
            assertThat(schema).contains("^[a-zA-Z0-9-_]+$");
        }

        @Test
        @DisplayName("schema should disallow additional properties")
        void schemaShouldDisallowAdditionalProperties() {
            String schema = plugin.getSchemaJson();

            assertThat(schema).contains("\"additionalProperties\": false");
        }
    }

    @Nested
    @DisplayName("onActivate()")
    class OnActivateMethod {

        @Test
        @DisplayName("should not throw exception on valid activation")
        void shouldNotThrowExceptionOnValidActivation() {
            // Given
            AccountPlugin accountPlugin = AccountPlugin.activate(
                UUID.randomUUID(),
                "bit-bi",
                Map.of("tenantId", "tenant-123")
            );

            // When / Then
            assertThatCode(() -> plugin.onActivate(accountPlugin))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should handle activation with various tenantId formats")
        void shouldHandleActivationWithVariousTenantIdFormats() {
            String[] validTenantIds = {"simple", "with-hyphen", "with_underscore", "ABC123"};

            for (String tenantId : validTenantIds) {
                AccountPlugin accountPlugin = AccountPlugin.activate(
                    UUID.randomUUID(),
                    "bit-bi",
                    Map.of("tenantId", tenantId)
                );

                assertThatCode(() -> plugin.onActivate(accountPlugin))
                    .as("Should handle tenantId: " + tenantId)
                    .doesNotThrowAnyException();
            }
        }
    }

    @Nested
    @DisplayName("onDeactivate()")
    class OnDeactivateMethod {

        @Test
        @DisplayName("should not throw exception on deactivation")
        void shouldNotThrowExceptionOnDeactivation() {
            // Given
            AccountPlugin accountPlugin = AccountPlugin.activate(
                UUID.randomUUID(),
                "bit-bi",
                Map.of("tenantId", "tenant-123")
            );

            // When / Then
            assertThatCode(() -> plugin.onDeactivate(accountPlugin))
                .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("execute()")
    class ExecuteMethod {

        @Test
        @DisplayName("should not throw exception for BATCH_COMPLETED event")
        void shouldNotThrowExceptionForBatchCompletedEvent() {
            // Given
            AccountPlugin accountPlugin = AccountPlugin.activate(
                UUID.randomUUID(),
                "bit-bi",
                Map.of("tenantId", "tenant-123")
            );

            PluginEvent event = new PluginEvent(
                UUID.randomUUID(),
                PluginEventType.BATCH_COMPLETED,
                accountPlugin.getAccountId(),
                UUID.randomUUID(), // resourceId (batch ID)
                Map.of(
                    "uploadedFilesCount", 5,
                    "totalSize", 1024L
                ),
                Instant.now()
            );

            // When / Then
            assertThatCode(() -> plugin.execute(event, accountPlugin))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should handle event with minimal metadata")
        void shouldHandleEventWithMinimalMetadata() {
            // Given
            AccountPlugin accountPlugin = AccountPlugin.activate(
                UUID.randomUUID(),
                "bit-bi",
                Map.of("tenantId", "tenant-123")
            );

            PluginEvent event = new PluginEvent(
                UUID.randomUUID(),
                PluginEventType.BATCH_COMPLETED,
                accountPlugin.getAccountId(),
                UUID.randomUUID(),
                Map.of(), // Empty metadata
                Instant.now()
            );

            // When / Then
            assertThatCode(() -> plugin.execute(event, accountPlugin))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should handle different event types gracefully")
        void shouldHandleDifferentEventTypesGracefully() {
            // Given
            AccountPlugin accountPlugin = AccountPlugin.activate(
                UUID.randomUUID(),
                "bit-bi",
                Map.of("tenantId", "tenant-123")
            );

            // Test all event types
            for (PluginEventType eventType : PluginEventType.values()) {
                PluginEvent event = new PluginEvent(
                    UUID.randomUUID(),
                    eventType,
                    accountPlugin.getAccountId(),
                    UUID.randomUUID(),
                    Map.of(),
                    Instant.now()
                );

                // When / Then - should not throw for any event type
                assertThatCode(() -> plugin.execute(event, accountPlugin))
                    .as("Should handle event type: " + eventType)
                    .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("should extract tenantId from accountPlugin")
        void shouldExtractTenantIdFromAccountPlugin() {
            // Given
            String expectedTenantId = "my-specific-tenant";
            AccountPlugin accountPlugin = AccountPlugin.activate(
                UUID.randomUUID(),
                "bit-bi",
                Map.of("tenantId", expectedTenantId)
            );

            PluginEvent event = new PluginEvent(
                UUID.randomUUID(),
                PluginEventType.BATCH_COMPLETED,
                accountPlugin.getAccountId(),
                UUID.randomUUID(),
                Map.of(),
                Instant.now()
            );

            // When / Then - execute should access tenantId without exception
            assertThatCode(() -> plugin.execute(event, accountPlugin))
                .doesNotThrowAnyException();
        }
    }

    // ==================== Feature 015: SQL Initialization on Activation ====================

    @Nested
    @DisplayName("initializeSqlFromLatestBatch() - Feature 015")
    class InitializeSqlFromLatestBatch {

        private BitBiPlugin pluginUnderTest;

        @BeforeEach
        void setUpMocks() {
            pluginUnderTest = new BitBiPlugin();
        }

        @Test
        @DisplayName("T008: Should log initialization with baseline batch when baseline exists")
        void shouldLogInitializationWithBaselineBatch() {
            // Given
            UUID accountId = UUID.randomUUID();
            UUID baselineBatchId = UUID.randomUUID();

            AccountPlugin accountPlugin = mock(AccountPlugin.class);
            when(accountPlugin.getAccountId()).thenReturn(accountId);
            when(accountPlugin.hasBaselineBatch()).thenReturn(true);
            when(accountPlugin.getBaselineBatchId()).thenReturn(baselineBatchId);

            // When / Then - should not throw (just logs)
            assertThatCode(() -> pluginUnderTest.initializeSqlFromLatestBatch(accountPlugin, true))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("T009: Should NOT log initialization on config update (isNewOrReactivation=false)")
        void shouldNotLogInitializationOnConfigUpdate() {
            // Given
            AccountPlugin accountPlugin = mock(AccountPlugin.class);
            when(accountPlugin.getAccountId()).thenReturn(UUID.randomUUID());

            // When / Then - should not throw and should return early
            assertThatCode(() -> pluginUnderTest.initializeSqlFromLatestBatch(accountPlugin, false))
                    .doesNotThrowAnyException();

            // Verify hasBaselineBatch() was never called (early return)
            verify(accountPlugin, never()).hasBaselineBatch();
        }

        @Test
        @DisplayName("T010: Should log initialization without baseline when no baseline exists")
        void shouldLogInitializationWithoutBaseline() {
            // Given
            UUID accountId = UUID.randomUUID();

            AccountPlugin accountPlugin = mock(AccountPlugin.class);
            when(accountPlugin.getAccountId()).thenReturn(accountId);
            when(accountPlugin.hasBaselineBatch()).thenReturn(false);

            // When / Then - should not throw (just logs)
            assertThatCode(() -> pluginUnderTest.initializeSqlFromLatestBatch(accountPlugin, true))
                    .doesNotThrowAnyException();
        }
    }

    // ==================== 026-bitbi-delta-sql: durable routing ====================

    @Nested
    @DisplayName("execute() durable SQL routing (026)")
    class DeltaV2Routing {

        private BitBiPlugin pluginUnderTest;
        private com.bitbi.dfm.plugin.application.DeltaSqlSweepWorker sweepWorker;

        private final UUID batchId = UUID.randomUUID();

        @BeforeEach
        void setUpMocks() {
            pluginUnderTest = new BitBiPlugin();
            sweepWorker = mock(com.bitbi.dfm.plugin.application.DeltaSqlSweepWorker.class);
            pluginUnderTest.setDeltaSqlSweepWorker(sweepWorker);
        }

        private PluginEvent batchCompletedEvent() {
            return PluginEvent.batchCompleted(UUID.randomUUID(), batchId, 0, 0L);
        }

        private AccountPlugin activation() {
            AccountPlugin accountPlugin = mock(AccountPlugin.class);
            when(accountPlugin.getId()).thenReturn(9L);
            when(accountPlugin.getPluginData()).thenReturn(java.util.Map.of("tenantId", "t1"));
            return accountPlugin;
        }

        @Test
        @DisplayName("should wake the sweep worker for completed batches")
        void shouldWakeWorkerForCompletedBatch() {
            pluginUnderTest.execute(batchCompletedEvent(), activation());

            verify(sweepWorker).wake();
        }
    }
}

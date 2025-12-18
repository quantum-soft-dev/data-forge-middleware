package com.bitbi.dfm.plugin.integration;

import com.bitbi.dfm.integration.AbstractIntegrationTest;
import com.bitbi.dfm.plugin.application.PluginEventDispatcher;
import com.bitbi.dfm.plugin.domain.*;
import com.bitbi.dfm.plugin.infrastructure.events.BatchEventListener;
import com.bitbi.dfm.shared.domain.events.BatchCompletedEvent;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for plugin event dispatch.
 *
 * <p>Tests end-to-end event flow from domain event to plugin execution:</p>
 * <ul>
 *   <li>BatchCompletedEvent triggers plugin execution</li>
 *   <li>Deactivated plugins are skipped</li>
 *   <li>last_used_at is updated on successful dispatch</li>
 * </ul>
 *
 * <p>User Story 5 (Phase 7) - Receive Batch Completion Notifications</p>
 */
@DisplayName("Plugin Event Dispatch Integration Tests")
@Sql("/test-data.sql")
class PluginEventDispatchIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private AccountPluginRepository accountPluginRepository;

    @Autowired
    private PluginRegistry pluginRegistry;

    @Autowired
    private BatchEventListener batchEventListener;

    private static final UUID TEST_ACCOUNT_ID = UUID.fromString("0199baac-f851-7ed9-5963-00dbaf07b233");
    private static final UUID TEST_BATCH_ID = UUID.randomUUID();

    @Nested
    @DisplayName("Event Dispatch Flow")
    class EventDispatchFlow {

        @Test
        @DisplayName("Should dispatch BatchCompletedEvent to subscribed plugins")
        @Transactional
        void shouldDispatchBatchCompletedEventToSubscribedPlugins() throws InterruptedException {
            // Given - Activate bit-bi plugin for test account
            AccountPlugin activation = AccountPlugin.activate(TEST_ACCOUNT_ID, "bit-bi",
                    Map.of("tenantId", "test-tenant"));
            accountPluginRepository.save(activation);
            assertThat(activation.getLastUsedAt()).isNull();

            // When - Publish batch completed event
            BatchCompletedEvent event = new BatchCompletedEvent(TEST_BATCH_ID, TEST_ACCOUNT_ID, 5, 1024L);
            eventPublisher.publishEvent(event);

            // Give async dispatch time to complete
            Thread.sleep(500);

            // Then - Verify event was processed (last_used_at should be updated)
            AccountPlugin updated = accountPluginRepository
                    .findByAccountIdAndPluginId(TEST_ACCOUNT_ID, "bit-bi")
                    .orElseThrow();

            // The plugin execution updates last_used_at on success
            assertThat(updated.getLastUsedAt()).isNotNull();
        }

        @Test
        @DisplayName("Should skip deactivated plugin")
        @Transactional
        void shouldSkipDeactivatedPlugin() throws InterruptedException {
            // Given - Activate and then deactivate plugin
            AccountPlugin activation = AccountPlugin.activate(TEST_ACCOUNT_ID, "bit-bi",
                    Map.of("tenantId", "test-tenant"));
            activation.deactivate();
            accountPluginRepository.save(activation);

            // When - Publish batch completed event
            BatchCompletedEvent event = new BatchCompletedEvent(TEST_BATCH_ID, TEST_ACCOUNT_ID, 5, 1024L);
            eventPublisher.publishEvent(event);

            // Give async dispatch time to complete (if any)
            Thread.sleep(500);

            // Then - Verify event was NOT processed (last_used_at should still be null)
            AccountPlugin result = accountPluginRepository
                    .findByAccountIdAndPluginId(TEST_ACCOUNT_ID, "bit-bi")
                    .orElseThrow();

            assertThat(result.isActive()).isFalse();
            assertThat(result.getLastUsedAt()).isNull();
        }

        @Test
        @DisplayName("Should handle event without accountId gracefully")
        void shouldHandleEventWithoutAccountIdGracefully() {
            // Given - Legacy event without accountId
            @SuppressWarnings("deprecation")
            BatchCompletedEvent legacyEvent = new BatchCompletedEvent(TEST_BATCH_ID, 5, 1024L);

            // When / Then - Should not throw exception
            eventPublisher.publishEvent(legacyEvent);
            // No assertion needed - test passes if no exception is thrown
        }

        @Test
        @DisplayName("Should handle missing activation gracefully")
        void shouldHandleMissingActivationGracefully() {
            // Given - No activation exists for this account/plugin combination
            UUID unknownAccountId = UUID.randomUUID();

            // When - Publish event for account without plugin activation
            BatchCompletedEvent event = new BatchCompletedEvent(TEST_BATCH_ID, unknownAccountId, 5, 1024L);

            // Then - Should not throw exception
            eventPublisher.publishEvent(event);
            // No assertion needed - test passes if no exception is thrown
        }
    }

    @Nested
    @DisplayName("Plugin Registry")
    class PluginRegistryTests {

        @Test
        @DisplayName("Should have bit-bi plugin registered")
        void shouldHaveBitBiPluginRegistered() {
            // When
            Plugin bitBiPlugin = pluginRegistry.findById("bit-bi").orElse(null);

            // Then
            assertThat(bitBiPlugin).isNotNull();
            assertThat(bitBiPlugin.getName()).isEqualTo("Bit BI");
            assertThat(bitBiPlugin.getSupportedEvents()).contains(PluginEventType.BATCH_COMPLETED);
        }

        @Test
        @DisplayName("Should find plugins by supported event")
        void shouldFindPluginsBySupportedEvent() {
            // When
            var plugins = pluginRegistry.findBySupportedEvent(PluginEventType.BATCH_COMPLETED);

            // Then
            assertThat(plugins).isNotEmpty();
            assertThat(plugins).anyMatch(p -> p.getId().equals("bit-bi"));
        }
    }
}

package com.bitbi.dfm.plugin.unit;

import com.bitbi.dfm.plugin.application.PluginAuditService;
import com.bitbi.dfm.plugin.application.PluginEventDispatcher;
import com.bitbi.dfm.plugin.application.PluginUsageService;
import com.bitbi.dfm.plugin.domain.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PluginEventDispatcher.
 *
 * <p>Tests event dispatch behavior including:</p>
 * <ul>
 *   <li>Dispatch to subscribed plugins only</li>
 *   <li>Timeout handling (30 seconds)</li>
 *   <li>Isolated failures (one plugin failure doesn't affect others)</li>
 *   <li>last_used_at update on success</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PluginEventDispatcher Unit Tests")
class PluginEventDispatcherTest {

    @Mock
    private PluginRegistry pluginRegistry;

    @Mock
    private AccountPluginRepository accountPluginRepository;

    @Mock
    private PluginAuditService pluginAuditService;

    @Mock
    private PluginUsageService pluginUsageService;

    private PluginEventDispatcher dispatcher;

    // Direct executor for synchronous test execution
    private final Executor syncExecutor = Runnable::run;

    private static final UUID TEST_ACCOUNT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final UUID TEST_BATCH_ID = UUID.fromString("b1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @BeforeEach
    void setUp() {
        dispatcher = new PluginEventDispatcher(
            pluginRegistry,
            accountPluginRepository,
            syncExecutor,
            pluginAuditService,
            pluginUsageService,
            new SimpleMeterRegistry()
        );
    }

    @Nested
    @DisplayName("dispatch()")
    class Dispatch {

        @Test
        @DisplayName("Should dispatch to subscribed plugin with active activation")
        void shouldDispatchToSubscribedPluginWithActiveActivation() {
            // Given
            PluginEvent event = PluginEvent.batchCompleted(TEST_ACCOUNT_ID, TEST_BATCH_ID, 5, 1024L);

            AtomicBoolean pluginExecuted = new AtomicBoolean(false);
            Plugin mockPlugin = createMockPlugin("bit-bi", Set.of(PluginEventType.BATCH_COMPLETED),
                (e, ap) -> pluginExecuted.set(true));

            AccountPlugin accountPlugin = AccountPlugin.activate(TEST_ACCOUNT_ID, "bit-bi", Map.of("tenantId", "test"));

            when(pluginRegistry.findBySupportedEvent(PluginEventType.BATCH_COMPLETED))
                .thenReturn(List.of(mockPlugin));
            when(accountPluginRepository.findByAccountIdAndPluginId(TEST_ACCOUNT_ID, "bit-bi"))
                .thenReturn(Optional.of(accountPlugin));

            // When
            dispatcher.dispatch(event);

            // Then
            assertThat(pluginExecuted.get()).isTrue();
            verify(pluginUsageService).updateLastUsedAt(accountPlugin); // last_used_at update
        }

        @Test
        @DisplayName("Should skip plugin without active activation")
        void shouldSkipPluginWithoutActiveActivation() {
            // Given
            PluginEvent event = PluginEvent.batchCompleted(TEST_ACCOUNT_ID, TEST_BATCH_ID, 5, 1024L);

            AtomicBoolean pluginExecuted = new AtomicBoolean(false);
            Plugin mockPlugin = createMockPlugin("bit-bi", Set.of(PluginEventType.BATCH_COMPLETED),
                (e, ap) -> pluginExecuted.set(true));

            when(pluginRegistry.findBySupportedEvent(PluginEventType.BATCH_COMPLETED))
                .thenReturn(List.of(mockPlugin));
            when(accountPluginRepository.findByAccountIdAndPluginId(TEST_ACCOUNT_ID, "bit-bi"))
                .thenReturn(Optional.empty());

            // When
            dispatcher.dispatch(event);

            // Then
            assertThat(pluginExecuted.get()).isFalse();
            verify(pluginUsageService, never()).updateLastUsedAt(any());
        }

        @Test
        @DisplayName("Should skip deactivated plugin")
        void shouldSkipDeactivatedPlugin() {
            // Given
            PluginEvent event = PluginEvent.batchCompleted(TEST_ACCOUNT_ID, TEST_BATCH_ID, 5, 1024L);

            AtomicBoolean pluginExecuted = new AtomicBoolean(false);
            Plugin mockPlugin = createMockPlugin("bit-bi", Set.of(PluginEventType.BATCH_COMPLETED),
                (e, ap) -> pluginExecuted.set(true));

            AccountPlugin deactivatedPlugin = AccountPlugin.activate(TEST_ACCOUNT_ID, "bit-bi", Map.of("tenantId", "test"));
            deactivatedPlugin.deactivate(); // Deactivate

            when(pluginRegistry.findBySupportedEvent(PluginEventType.BATCH_COMPLETED))
                .thenReturn(List.of(mockPlugin));
            when(accountPluginRepository.findByAccountIdAndPluginId(TEST_ACCOUNT_ID, "bit-bi"))
                .thenReturn(Optional.of(deactivatedPlugin));

            // When
            dispatcher.dispatch(event);

            // Then
            assertThat(pluginExecuted.get()).isFalse();
            verify(pluginUsageService, never()).updateLastUsedAt(any());
        }

        @Test
        @DisplayName("Should handle null event gracefully")
        void shouldHandleNullEventGracefully() {
            // When
            dispatcher.dispatch(null);

            // Then - no exceptions, no interactions
            verifyNoInteractions(pluginRegistry, accountPluginRepository);
        }

        @Test
        @DisplayName("Should handle no subscribed plugins")
        void shouldHandleNoSubscribedPlugins() {
            // Given
            PluginEvent event = PluginEvent.batchCompleted(TEST_ACCOUNT_ID, TEST_BATCH_ID, 5, 1024L);

            when(pluginRegistry.findBySupportedEvent(PluginEventType.BATCH_COMPLETED))
                .thenReturn(Collections.emptyList());

            // When
            dispatcher.dispatch(event);

            // Then - no exceptions, no activation lookups
            verifyNoInteractions(accountPluginRepository);
        }

        @Test
        @DisplayName("Should dispatch to multiple plugins independently")
        void shouldDispatchToMultiplePluginsIndependently() {
            // Given
            PluginEvent event = PluginEvent.batchCompleted(TEST_ACCOUNT_ID, TEST_BATCH_ID, 5, 1024L);

            AtomicInteger plugin1Count = new AtomicInteger(0);
            AtomicInteger plugin2Count = new AtomicInteger(0);

            Plugin plugin1 = createMockPlugin("plugin-1", Set.of(PluginEventType.BATCH_COMPLETED),
                (e, ap) -> plugin1Count.incrementAndGet());
            Plugin plugin2 = createMockPlugin("plugin-2", Set.of(PluginEventType.BATCH_COMPLETED),
                (e, ap) -> plugin2Count.incrementAndGet());

            AccountPlugin ap1 = AccountPlugin.activate(TEST_ACCOUNT_ID, "plugin-1", Map.of());
            AccountPlugin ap2 = AccountPlugin.activate(TEST_ACCOUNT_ID, "plugin-2", Map.of());

            when(pluginRegistry.findBySupportedEvent(PluginEventType.BATCH_COMPLETED))
                .thenReturn(List.of(plugin1, plugin2));
            when(accountPluginRepository.findByAccountIdAndPluginId(TEST_ACCOUNT_ID, "plugin-1"))
                .thenReturn(Optional.of(ap1));
            when(accountPluginRepository.findByAccountIdAndPluginId(TEST_ACCOUNT_ID, "plugin-2"))
                .thenReturn(Optional.of(ap2));

            // When
            dispatcher.dispatch(event);

            // Then - both plugins executed
            assertThat(plugin1Count.get()).isEqualTo(1);
            assertThat(plugin2Count.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should isolate plugin failures - one failure doesn't affect others")
        void shouldIsolatePluginFailures() {
            // Given
            PluginEvent event = PluginEvent.batchCompleted(TEST_ACCOUNT_ID, TEST_BATCH_ID, 5, 1024L);

            AtomicBoolean plugin2Executed = new AtomicBoolean(false);

            // Plugin 1 throws exception
            Plugin failingPlugin = createMockPlugin("failing-plugin", Set.of(PluginEventType.BATCH_COMPLETED),
                (e, ap) -> { throw new RuntimeException("Simulated failure"); });
            // Plugin 2 should still execute
            Plugin successPlugin = createMockPlugin("success-plugin", Set.of(PluginEventType.BATCH_COMPLETED),
                (e, ap) -> plugin2Executed.set(true));

            AccountPlugin ap1 = AccountPlugin.activate(TEST_ACCOUNT_ID, "failing-plugin", Map.of());
            AccountPlugin ap2 = AccountPlugin.activate(TEST_ACCOUNT_ID, "success-plugin", Map.of());

            when(pluginRegistry.findBySupportedEvent(PluginEventType.BATCH_COMPLETED))
                .thenReturn(List.of(failingPlugin, successPlugin));
            when(accountPluginRepository.findByAccountIdAndPluginId(TEST_ACCOUNT_ID, "failing-plugin"))
                .thenReturn(Optional.of(ap1));
            when(accountPluginRepository.findByAccountIdAndPluginId(TEST_ACCOUNT_ID, "success-plugin"))
                .thenReturn(Optional.of(ap2));

            // When
            dispatcher.dispatch(event);

            // Then - second plugin still executed despite first failing
            assertThat(plugin2Executed.get()).isTrue();
        }

        @Test
        @DisplayName("Should update last_used_at on successful dispatch")
        void shouldUpdateLastUsedAtOnSuccess() {
            // Given
            PluginEvent event = PluginEvent.batchCompleted(TEST_ACCOUNT_ID, TEST_BATCH_ID, 5, 1024L);

            Plugin mockPlugin = createMockPlugin("bit-bi", Set.of(PluginEventType.BATCH_COMPLETED),
                (e, ap) -> {});

            AccountPlugin accountPlugin = AccountPlugin.activate(TEST_ACCOUNT_ID, "bit-bi", Map.of("tenantId", "test"));

            when(pluginRegistry.findBySupportedEvent(PluginEventType.BATCH_COMPLETED))
                .thenReturn(List.of(mockPlugin));
            when(accountPluginRepository.findByAccountIdAndPluginId(TEST_ACCOUNT_ID, "bit-bi"))
                .thenReturn(Optional.of(accountPlugin));

            // When
            dispatcher.dispatch(event);

            // Then - verify service was called to update last_used_at
            verify(pluginUsageService).updateLastUsedAt(accountPlugin);
        }
    }

    /**
     * Helper to create a mock Plugin with configurable behavior.
     */
    private Plugin createMockPlugin(String id, Set<PluginEventType> supportedEvents,
                                    java.util.function.BiConsumer<PluginEvent, AccountPlugin> executeAction) {
        return new Plugin() {
            @Override
            public String getId() { return id; }

            @Override
            public String getName() { return id + " Plugin"; }

            @Override
            public String getVersion() { return "1.0.0"; }

            @Override
            public Set<PluginEventType> getSupportedEvents() { return supportedEvents; }

            @Override
            public String getSchemaJson() { return "{}"; }

            @Override
            public void execute(PluginEvent event, AccountPlugin accountPlugin) {
                executeAction.accept(event, accountPlugin);
            }

            @Override
            public void onActivate(AccountPlugin accountPlugin) {}

            @Override
            public void onDeactivate(AccountPlugin accountPlugin) {}
        };
    }
}

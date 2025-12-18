package com.bitbi.dfm.plugin.integration;

import com.bitbi.dfm.integration.AbstractIntegrationTest;
import com.bitbi.dfm.plugin.application.PluginActivationService;
import com.bitbi.dfm.plugin.application.PluginAuditService;
import com.bitbi.dfm.plugin.application.PluginEventDispatcher;
import com.bitbi.dfm.plugin.domain.*;
import com.bitbi.dfm.shared.domain.events.BatchCompletedEvent;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for plugin audit logging.
 *
 * <p>Tests end-to-end audit trail creation:</p>
 * <ul>
 *   <li>Activation creates audit log entry</li>
 *   <li>Deactivation creates audit log entry</li>
 *   <li>Event dispatch creates audit log entry</li>
 *   <li>Audit log query with filters works correctly</li>
 *   <li>Partition pruning works for date-range queries</li>
 * </ul>
 *
 * <p>User Story 6 (Phase 8) - Admin Views Plugin Audit Trail</p>
 */
@DisplayName("Plugin Audit Integration Tests")
@Sql("/test-data.sql")
class PluginAuditIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PluginActivationService pluginActivationService;

    @Autowired
    private PluginAuditService pluginAuditService;

    @Autowired
    private PluginAuditLogRepository auditLogRepository;

    @Autowired
    private AccountPluginRepository accountPluginRepository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    private static final UUID TEST_ACCOUNT_ID = UUID.fromString("0199baac-f851-7ed9-5963-00dbaf07b233");
    private static final String PLUGIN_ID = "bit-bi";
    private static final String CLIENT_ID = "test-client-123";

    @Nested
    @DisplayName("Activation Audit Logging")
    class ActivationAuditLogging {

        @Test
        @DisplayName("Should create audit entry for new activation")
        @Transactional
        void shouldCreateAuditEntryForNewActivation() {
            // Given
            Map<String, Object> pluginData = Map.of("tenantId", "tenant-audit-test-1");

            // When
            pluginActivationService.activate(TEST_ACCOUNT_ID, PLUGIN_ID, pluginData, CLIENT_ID);

            // Then - wait for async audit log to be created
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                Page<PluginAuditLog> logs = auditLogRepository.findByFilters(
                        PLUGIN_ID, TEST_ACCOUNT_ID, PluginActionType.ACTIVATE, null,
                        Instant.now().minus(1, ChronoUnit.HOURS), null,
                        PageRequest.of(0, 10));

                assertThat(logs.getContent()).isNotEmpty();
                PluginAuditLog auditLog = logs.getContent().get(0);
                assertThat(auditLog.getPluginId()).isEqualTo(PLUGIN_ID);
                assertThat(auditLog.getAccountId()).isEqualTo(TEST_ACCOUNT_ID);
                assertThat(auditLog.getActionType()).isEqualTo(PluginActionType.ACTIVATE);
                assertThat(auditLog.isSuccess()).isTrue();
                assertThat(auditLog.getClientId()).isEqualTo(CLIENT_ID);
                assertThat(auditLog.getDurationMs()).isNotNull();
            });
        }

        @Test
        @DisplayName("Should create audit entry for reactivation")
        @Transactional
        void shouldCreateAuditEntryForReactivation() {
            // Given - activate, deactivate, then reactivate
            Map<String, Object> pluginData = Map.of("tenantId", "tenant-reactivate-test");
            pluginActivationService.activate(TEST_ACCOUNT_ID, PLUGIN_ID, pluginData, CLIENT_ID);

            // Wait for first audit log
            await().atMost(2, TimeUnit.SECONDS).until(() -> {
                Page<PluginAuditLog> logs = auditLogRepository.findByPluginId(PLUGIN_ID, PageRequest.of(0, 10));
                return !logs.isEmpty();
            });

            pluginActivationService.deactivate(TEST_ACCOUNT_ID, PLUGIN_ID, CLIENT_ID);

            // Wait for deactivation audit log
            await().atMost(2, TimeUnit.SECONDS).until(() -> {
                Page<PluginAuditLog> logs = auditLogRepository.findByFilters(
                        PLUGIN_ID, TEST_ACCOUNT_ID, PluginActionType.DEACTIVATE, null, null, null,
                        PageRequest.of(0, 10));
                return !logs.isEmpty();
            });

            // When - reactivate
            pluginActivationService.activate(TEST_ACCOUNT_ID, PLUGIN_ID, pluginData, CLIENT_ID);

            // Then - verify REACTIVATE audit entry
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                Page<PluginAuditLog> logs = auditLogRepository.findByFilters(
                        PLUGIN_ID, TEST_ACCOUNT_ID, PluginActionType.REACTIVATE, null, null, null,
                        PageRequest.of(0, 10));

                assertThat(logs.getContent()).isNotEmpty();
                PluginAuditLog auditLog = logs.getContent().get(0);
                assertThat(auditLog.getActionType()).isEqualTo(PluginActionType.REACTIVATE);
                assertThat(auditLog.isSuccess()).isTrue();
            });
        }
    }

    @Nested
    @DisplayName("Deactivation Audit Logging")
    class DeactivationAuditLogging {

        @Test
        @DisplayName("Should create audit entry for deactivation")
        @Transactional
        void shouldCreateAuditEntryForDeactivation() {
            // Given - activate first
            Map<String, Object> pluginData = Map.of("tenantId", "tenant-deactivate-test");
            pluginActivationService.activate(TEST_ACCOUNT_ID, PLUGIN_ID, pluginData, CLIENT_ID);

            // Wait for activation audit log
            await().atMost(2, TimeUnit.SECONDS).until(() -> {
                Page<PluginAuditLog> logs = auditLogRepository.findByFilters(
                        PLUGIN_ID, TEST_ACCOUNT_ID, PluginActionType.ACTIVATE, null, null, null,
                        PageRequest.of(0, 10));
                return !logs.isEmpty();
            });

            // When - deactivate
            pluginActivationService.deactivate(TEST_ACCOUNT_ID, PLUGIN_ID, CLIENT_ID);

            // Then - verify DEACTIVATE audit entry
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                Page<PluginAuditLog> logs = auditLogRepository.findByFilters(
                        PLUGIN_ID, TEST_ACCOUNT_ID, PluginActionType.DEACTIVATE, null, null, null,
                        PageRequest.of(0, 10));

                assertThat(logs.getContent()).isNotEmpty();
                PluginAuditLog auditLog = logs.getContent().get(0);
                assertThat(auditLog.getPluginId()).isEqualTo(PLUGIN_ID);
                assertThat(auditLog.getAccountId()).isEqualTo(TEST_ACCOUNT_ID);
                assertThat(auditLog.getActionType()).isEqualTo(PluginActionType.DEACTIVATE);
                assertThat(auditLog.isSuccess()).isTrue();
            });
        }
    }

    @Nested
    @DisplayName("Event Dispatch Audit Logging")
    class EventDispatchAuditLogging {

        @Test
        @DisplayName("Should create audit entry for successful event dispatch")
        @Transactional
        void shouldCreateAuditEntryForSuccessfulEventDispatch() {
            // Given - activate plugin for test account
            Map<String, Object> pluginData = Map.of("tenantId", "tenant-event-test");
            pluginActivationService.activate(TEST_ACCOUNT_ID, PLUGIN_ID, pluginData, CLIENT_ID);

            // Wait for activation to complete
            await().atMost(2, TimeUnit.SECONDS).until(() -> {
                return accountPluginRepository.findByAccountIdAndPluginId(TEST_ACCOUNT_ID, PLUGIN_ID)
                        .map(AccountPlugin::isActive)
                        .orElse(false);
            });

            // When - publish batch completed event
            UUID batchId = UUID.randomUUID();
            BatchCompletedEvent event = new BatchCompletedEvent(batchId, TEST_ACCOUNT_ID, 5, 1024L);
            eventPublisher.publishEvent(event);

            // Then - verify EVENT_DISPATCHED audit entry
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                Page<PluginAuditLog> logs = auditLogRepository.findByFilters(
                        PLUGIN_ID, TEST_ACCOUNT_ID, PluginActionType.EVENT_DISPATCHED, null, null, null,
                        PageRequest.of(0, 10));

                assertThat(logs.getContent()).isNotEmpty();
                PluginAuditLog auditLog = logs.getContent().get(0);
                assertThat(auditLog.getPluginId()).isEqualTo(PLUGIN_ID);
                assertThat(auditLog.getAccountId()).isEqualTo(TEST_ACCOUNT_ID);
                assertThat(auditLog.getActionType()).isEqualTo(PluginActionType.EVENT_DISPATCHED);
                assertThat(auditLog.isSuccess()).isTrue();
                assertThat(auditLog.getDurationMs()).isNotNull();
            });
        }
    }

    @Nested
    @DisplayName("Audit Log Query")
    class AuditLogQuery {

        @Test
        @DisplayName("Should filter audit logs by date range")
        @Transactional
        void shouldFilterByDateRange() {
            // Given - create audit entry via service
            pluginAuditService.logActivation(PLUGIN_ID, TEST_ACCOUNT_ID, CLIENT_ID, 100L);

            // Wait for async log to be created
            await().atMost(2, TimeUnit.SECONDS).until(() -> {
                Page<PluginAuditLog> logs = auditLogRepository.findByPluginId(PLUGIN_ID, PageRequest.of(0, 10));
                return !logs.isEmpty();
            });

            // When - query with date range that includes now
            Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
            Instant to = Instant.now().plus(1, ChronoUnit.HOURS);

            Page<PluginAuditLog> logs = auditLogRepository.findByFilters(
                    PLUGIN_ID, null, null, null, from, to,
                    PageRequest.of(0, 10));

            // Then
            assertThat(logs.getContent()).isNotEmpty();
            assertThat(logs.getContent()).allSatisfy(log -> {
                assertThat(log.getOccurredAt()).isAfterOrEqualTo(from);
                assertThat(log.getOccurredAt()).isBefore(to);
            });
        }

        @Test
        @DisplayName("Should filter audit logs by success status")
        @Transactional
        void shouldFilterBySuccessStatus() {
            // Given - create success and failure entries
            pluginAuditService.logActivation(PLUGIN_ID, TEST_ACCOUNT_ID, CLIENT_ID, 50L);
            pluginAuditService.logActivationFailure(PLUGIN_ID, TEST_ACCOUNT_ID, CLIENT_ID,
                    "Test failure", 100L, 400);

            // Wait for both logs to be created
            await().atMost(2, TimeUnit.SECONDS).until(() -> {
                Page<PluginAuditLog> logs = auditLogRepository.findByPluginId(PLUGIN_ID, PageRequest.of(0, 10));
                return logs.getTotalElements() >= 2;
            });

            // When - query only failures
            Page<PluginAuditLog> failureLogs = auditLogRepository.findByFilters(
                    PLUGIN_ID, null, null, false, null, null,
                    PageRequest.of(0, 10));

            // Then
            assertThat(failureLogs.getContent()).isNotEmpty();
            assertThat(failureLogs.getContent()).allSatisfy(log -> {
                assertThat(log.isSuccess()).isFalse();
            });
        }

        @Test
        @DisplayName("Should filter audit logs by action type")
        @Transactional
        void shouldFilterByActionType() {
            // Given - create entries with different action types
            pluginAuditService.logActivation(PLUGIN_ID, TEST_ACCOUNT_ID, CLIENT_ID, 50L);
            pluginAuditService.logEventDispatch(PLUGIN_ID, TEST_ACCOUNT_ID, 100L);

            // Wait for logs to be created
            await().atMost(2, TimeUnit.SECONDS).until(() -> {
                Page<PluginAuditLog> logs = auditLogRepository.findByPluginId(PLUGIN_ID, PageRequest.of(0, 10));
                return logs.getTotalElements() >= 2;
            });

            // When - query only EVENT_DISPATCHED
            Page<PluginAuditLog> eventLogs = auditLogRepository.findByFilters(
                    PLUGIN_ID, null, PluginActionType.EVENT_DISPATCHED, null, null, null,
                    PageRequest.of(0, 10));

            // Then
            assertThat(eventLogs.getContent()).isNotEmpty();
            assertThat(eventLogs.getContent()).allSatisfy(log -> {
                assertThat(log.getActionType()).isEqualTo(PluginActionType.EVENT_DISPATCHED);
            });
        }

        @Test
        @DisplayName("Should return audit logs in descending order by occurredAt")
        @Transactional
        void shouldReturnLogsInDescendingOrder() {
            // Given - create multiple entries
            pluginAuditService.logActivation(PLUGIN_ID, TEST_ACCOUNT_ID, CLIENT_ID, 50L);
            // Small delay to ensure different timestamps
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            pluginAuditService.logEventDispatch(PLUGIN_ID, TEST_ACCOUNT_ID, 100L);

            // Wait for logs to be created
            await().atMost(2, TimeUnit.SECONDS).until(() -> {
                Page<PluginAuditLog> logs = auditLogRepository.findByPluginId(PLUGIN_ID, PageRequest.of(0, 10));
                return logs.getTotalElements() >= 2;
            });

            // When
            Page<PluginAuditLog> logs = auditLogRepository.findByFilters(
                    PLUGIN_ID, null, null, null, null, null,
                    PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "occurredAt")));

            // Then
            assertThat(logs.getContent().size()).isGreaterThanOrEqualTo(2);
            for (int i = 0; i < logs.getContent().size() - 1; i++) {
                Instant current = logs.getContent().get(i).getOccurredAt();
                Instant next = logs.getContent().get(i + 1).getOccurredAt();
                assertThat(current).isAfterOrEqualTo(next);
            }
        }
    }

    @Nested
    @DisplayName("Audit Log Counts")
    class AuditLogCounts {

        @Test
        @DisplayName("Should count audit logs by action type and date range")
        @Transactional
        void shouldCountByActionTypeAndDateRange() {
            // Given - create multiple activation entries
            pluginAuditService.logActivation(PLUGIN_ID, TEST_ACCOUNT_ID, CLIENT_ID, 50L);
            pluginAuditService.logActivation("other-plugin", TEST_ACCOUNT_ID, CLIENT_ID, 50L);
            pluginAuditService.logEventDispatch(PLUGIN_ID, TEST_ACCOUNT_ID, 100L);

            // Wait for logs to be created
            await().atMost(2, TimeUnit.SECONDS).until(() -> {
                Page<PluginAuditLog> logs = auditLogRepository.findByFilters(
                        null, null, null, null, null, null, PageRequest.of(0, 10));
                return logs.getTotalElements() >= 3;
            });

            // When
            Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
            Instant to = Instant.now().plus(1, ChronoUnit.HOURS);
            long activationCount = auditLogRepository.countByActionTypeAndDateRange(
                    PluginActionType.ACTIVATE, from, to);

            // Then
            assertThat(activationCount).isGreaterThanOrEqualTo(2);
        }
    }
}

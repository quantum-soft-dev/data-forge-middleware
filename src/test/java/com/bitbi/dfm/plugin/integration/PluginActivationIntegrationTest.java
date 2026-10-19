package com.bitbi.dfm.plugin.integration;

import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.integration.AbstractIntegrationTest;
import com.bitbi.dfm.plugin.application.PluginActivationService;
import com.bitbi.dfm.plugin.application.PluginApiKeyService;
import com.bitbi.dfm.plugin.domain.*;
import com.bitbi.dfm.plugin.domain.exception.PluginDataValidationException;
import com.bitbi.dfm.plugin.domain.exception.PluginNotActivatedException;
import com.bitbi.dfm.plugin.domain.exception.PluginNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for plugin activation/deactivation.
 *
 * <p>Tests end-to-end activation flow with real database:</p>
 * <ul>
 *   <li>New activation creates AccountPlugin record</li>
 *   <li>Update activation modifies existing record</li>
 *   <li>Reactivation sets is_active=true, clears deactivated_at</li>
 *   <li>JSON Schema validation rejects invalid pluginData</li>
 *   <li>Concurrent activation requests handled correctly</li>
 * </ul>
 *
 * <p>User Story 2 (Phase 3) - Activate a Plugin for an Account</p>
 */
@DisplayName("Plugin Activation Integration Tests")
@Sql("/test-data.sql")
class PluginActivationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PluginActivationService pluginActivationService;

    @Autowired
    private AccountPluginRepository accountPluginRepository;

    @Autowired
    private PluginRegistry pluginRegistry;

    @Autowired
    private PluginApiKeyService pluginApiKeyService;

    @Autowired
    private PluginDeltaBaselineRepository deltaBaselineRepository;

    @Autowired
    private CheckpointRepository checkpointRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private static final UUID TEST_ACCOUNT_ID = UUID.fromString("0199baac-f851-7ed9-5963-00dbaf07b233");
    private static final String BIT_BI_PLUGIN_ID = "bit-bi";

    /** MOCK account of test-data.sql: owns store-01 and completed batches, unlike TEST_ACCOUNT_ID. */
    private static final UUID SITE_OWNER_ACCOUNT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final UUID STORE_01_SITE_ID = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    private static final String BASELINE_TABLE = "t383_orders";

    @Nested
    @DisplayName("New Activation")
    class NewActivation {

        @Test
        @DisplayName("Should create AccountPlugin record on new activation")
        @Transactional
        void shouldCreateAccountPluginRecordOnNewActivation() {
            // Given
            Map<String, Object> pluginData = Map.of("tenantId", "test-tenant-123");

            // When
            PluginActivationService.ActivationResult result = pluginActivationService.activate(
                    TEST_ACCOUNT_ID, BIT_BI_PLUGIN_ID, pluginData);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.isNewActivation()).isTrue();
            assertThat(result.pluginDisplayName()).isEqualTo("Bit BI");

            AccountPlugin accountPlugin = result.accountPlugin();
            assertThat(accountPlugin.getId()).isNotNull();
            assertThat(accountPlugin.getAccountId()).isEqualTo(TEST_ACCOUNT_ID);
            assertThat(accountPlugin.getPluginId()).isEqualTo(BIT_BI_PLUGIN_ID);
            assertThat(accountPlugin.isActive()).isTrue();
            assertThat(accountPlugin.getActivatedAt()).isNotNull();
            assertThat(accountPlugin.getDeactivatedAt()).isNull();
            assertThat(accountPlugin.getPluginData()).containsEntry("tenantId", "test-tenant-123");

            // Verify persisted
            AccountPlugin persisted = accountPluginRepository
                    .findByAccountIdAndPluginId(TEST_ACCOUNT_ID, BIT_BI_PLUGIN_ID)
                    .orElseThrow();
            assertThat(persisted.getId()).isEqualTo(accountPlugin.getId());
        }

        @Test
        @DisplayName("Should throw PluginNotFoundException for unknown plugin")
        void shouldThrowPluginNotFoundExceptionForUnknownPlugin() {
            // Given
            Map<String, Object> pluginData = Map.of("tenantId", "test-tenant");

            // When / Then
            assertThatThrownBy(() ->
                    pluginActivationService.activate(TEST_ACCOUNT_ID, "unknown-plugin", pluginData))
                    .isInstanceOf(PluginNotFoundException.class)
                    .hasMessageContaining("unknown-plugin");
        }

        @Test
        @DisplayName("Should throw PluginDataValidationException for invalid pluginData")
        @Transactional
        void shouldThrowPluginDataValidationExceptionForInvalidPluginData() {
            // Given - Missing required tenantId field
            Map<String, Object> invalidData = Map.of("invalidField", "value");

            // When / Then
            assertThatThrownBy(() ->
                    pluginActivationService.activate(TEST_ACCOUNT_ID, BIT_BI_PLUGIN_ID, invalidData))
                    .isInstanceOf(PluginDataValidationException.class)
                    .hasMessageContaining("tenantId");
        }
    }

    @Nested
    @DisplayName("Update Existing Activation")
    class UpdateActivation {

        @Test
        @DisplayName("Should update pluginData for already active plugin")
        @Transactional
        void shouldUpdatePluginDataForAlreadyActivePlugin() {
            // Given - Create initial activation
            Map<String, Object> initialData = Map.of("tenantId", "initial-tenant");
            PluginActivationService.ActivationResult initial = pluginActivationService.activate(
                    TEST_ACCOUNT_ID, BIT_BI_PLUGIN_ID, initialData);
            assertThat(initial.isNewActivation()).isTrue();

            // When - Update with new data
            Map<String, Object> updatedData = Map.of("tenantId", "updated-tenant");
            PluginActivationService.ActivationResult updated = pluginActivationService.activate(
                    TEST_ACCOUNT_ID, BIT_BI_PLUGIN_ID, updatedData);

            // Then
            assertThat(updated.isNewActivation()).isFalse();
            assertThat(updated.accountPlugin().getId()).isEqualTo(initial.accountPlugin().getId());
            assertThat(updated.accountPlugin().getPluginData()).containsEntry("tenantId", "updated-tenant");
            assertThat(updated.accountPlugin().isActive()).isTrue();
        }
    }

    /**
     * A second {@code /activate} on an active bit-bi has no side effects (issue #383).
     *
     * <p>Driven through the real {@code BitBiPlugin}, because the side effects live in its
     * {@code onActivate} — a mocked hook cannot show that the key was re-issued or the baselines
     * moved. Between the two calls the account gains a newer completed batch and the site's
     * checkpoint moves forward, so a hook that ran again would visibly move both.</p>
     */
    @Nested
    @DisplayName("Repeated activation of an active bit-bi (#383)")
    class RepeatedActivation {

        @Test
        @DisplayName("Should keep the API key, the baseline batch and the delta baselines")
        @Transactional
        void shouldKeepApiKeyBaselineBatchAndDeltaBaselinesOnRepeatedActivation() {
            Checkpoint checkpoint = checkpointRepository.save(
                    Checkpoint.create(STORE_01_SITE_ID, BASELINE_TABLE, 10L, 5L));

            PluginActivationService.ActivationResult first = pluginActivationService.activate(
                    SITE_OWNER_ACCOUNT_ID, BIT_BI_PLUGIN_ID, Map.of("tenantId", "first-tenant"));
            assertThat(first.isNewActivation()).isTrue();
            String issuedKey = first.apiKey();
            assertThat(issuedKey).as("the first activation shows its key").isNotNull();
            Long activationId = first.accountPlugin().getId();
            UUID baselineBatchBefore = first.accountPlugin().getBaselineBatchId();
            assertThat(baselineBatchBefore).as("the first activation sets a baseline batch").isNotNull();
            assertThat(baselinesOf(activationId)).containsEntry(BASELINE_TABLE, 10L);

            UUID newerBatchId = insertNewerCompletedBatch();
            checkpoint.update(20L, 7L);
            checkpointRepository.save(checkpoint);

            PluginActivationService.ActivationResult second = pluginActivationService.activate(
                    SITE_OWNER_ACCOUNT_ID, BIT_BI_PLUGIN_ID, Map.of("tenantId", "second-tenant"));

            assertThat(second.isNewActivation()).isFalse();
            assertThat(second.apiKey()).as("a repeated activation shows no key").isNull();
            assertThat(second.accountPlugin().getId()).isEqualTo(activationId);
            assertThat(second.accountPlugin().getPluginData()).containsEntry("tenantId", "second-tenant");

            // validateApiKey is readOnly and joins this test's transaction, which leaves the
            // pending key write unflushed — flush so the lookup reads what was written.
            entityManager.flush();
            assertThat(pluginApiKeyService.validateApiKey(issuedKey))
                    .as("the key issued by the first activation still authenticates")
                    .map(AccountPlugin::getId)
                    .contains(activationId);

            assertThat(storedBaselineBatchId(activationId))
                    .as("baseline_batch_id stays where the first activation put it")
                    .isEqualTo(baselineBatchBefore)
                    .isNotEqualTo(newerBatchId);
            assertThat(baselinesOf(activationId))
                    .as("plugin_delta_baselines are not re-captured from the newer checkpoint")
                    .containsEntry(BASELINE_TABLE, 10L);
        }

        /**
         * Found working #383: the delta-baseline capture in {@code onActivate} cleared the
         * persistence context without flushing it, so the {@code baseline_batch_id} it had just set
         * on an account with sites never reached the row.
         */
        @Test
        @DisplayName("Should persist the baseline batch a new activation sets")
        @Transactional
        void shouldPersistBaselineBatchOfNewActivation() {
            PluginActivationService.ActivationResult first = pluginActivationService.activate(
                    SITE_OWNER_ACCOUNT_ID, BIT_BI_PLUGIN_ID, Map.of("tenantId", "first-tenant"));

            assertThat(storedBaselineBatchId(first.accountPlugin().getId()))
                    .as("the account's latest completed batch, as stored in the row")
                    .isNotNull()
                    .isEqualTo(first.accountPlugin().getBaselineBatchId());
        }

        @Test
        @DisplayName("Should still issue and return a new key on reactivation")
        @Transactional
        void shouldIssueNewKeyOnReactivation() {
            PluginActivationService.ActivationResult first = pluginActivationService.activate(
                    SITE_OWNER_ACCOUNT_ID, BIT_BI_PLUGIN_ID, Map.of("tenantId", "first-tenant"));
            String firstKey = first.apiKey();
            assertThat(firstKey).isNotNull();

            pluginActivationService.deactivate(SITE_OWNER_ACCOUNT_ID, BIT_BI_PLUGIN_ID);
            PluginActivationService.ActivationResult reactivated = pluginActivationService.activate(
                    SITE_OWNER_ACCOUNT_ID, BIT_BI_PLUGIN_ID, Map.of("tenantId", "first-tenant"));

            assertThat(reactivated.apiKey()).as("reactivation shows a new key").isNotNull().isNotEqualTo(firstKey);
            entityManager.flush();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT is_active FROM account_plugins WHERE id = ?", Boolean.class, first.accountPlugin().getId()))
                    .as("the reactivation reached the row")
                    .isTrue();
            assertThat(pluginApiKeyService.validateApiKey(reactivated.apiKey())).isPresent();
            assertThat(pluginApiKeyService.validateApiKey(firstKey))
                    .as("the key of the previous activation no longer authenticates")
                    .isEmpty();
        }

        /** The raw column, read after a flush — the persistence context would answer with its own copy. */
        private UUID storedBaselineBatchId(Long activationId) {
            entityManager.flush();
            return jdbcTemplate.queryForObject(
                    "SELECT baseline_batch_id FROM account_plugins WHERE id = ?", UUID.class, activationId);
        }

        private Map<String, Long> baselinesOf(Long activationId) {
            Map<String, Long> seqs = new HashMap<>();
            deltaBaselineRepository.findByAccountPluginIdAndSiteId(activationId, STORE_01_SITE_ID)
                    .forEach(b -> seqs.put(b.getTableName(), b.getBaselineSeq()));
            return seqs;
        }

        private UUID insertNewerCompletedBatch() {
            UUID batchId = UUID.randomUUID();
            jdbcTemplate.update("""
                    INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count,
                                         total_size, has_errors, started_at, created_at, completed_at)
                    VALUES (?, ?, ?, 'COMPLETED', ?, 0, 0, false,
                            '2999-01-01 00:00:00', '2999-01-01 00:00:00', '2999-01-01 00:10:00')
                    """, batchId, SITE_OWNER_ACCOUNT_ID, STORE_01_SITE_ID,
                    SITE_OWNER_ACCOUNT_ID + "/store-01.example.com/2999-01-01/00-00/");
            return batchId;
        }
    }

    @Nested
    @DisplayName("Reactivation")
    class Reactivation {

        @Test
        @DisplayName("Should reactivate deactivated plugin")
        @Transactional
        void shouldReactivateDeactivatedPlugin() {
            // Given - Create and deactivate
            Map<String, Object> pluginData = Map.of("tenantId", "test-tenant");
            PluginActivationService.ActivationResult initial = pluginActivationService.activate(
                    TEST_ACCOUNT_ID, BIT_BI_PLUGIN_ID, pluginData);

            pluginActivationService.deactivate(TEST_ACCOUNT_ID, BIT_BI_PLUGIN_ID);

            AccountPlugin deactivated = accountPluginRepository
                    .findByAccountIdAndPluginId(TEST_ACCOUNT_ID, BIT_BI_PLUGIN_ID)
                    .orElseThrow();
            assertThat(deactivated.isActive()).isFalse();
            assertThat(deactivated.getDeactivatedAt()).isNotNull();

            // When - Reactivate with new data
            Map<String, Object> newData = Map.of("tenantId", "reactivated-tenant");
            PluginActivationService.ActivationResult reactivated = pluginActivationService.activate(
                    TEST_ACCOUNT_ID, BIT_BI_PLUGIN_ID, newData);

            // Then
            assertThat(reactivated.isNewActivation()).isFalse();
            assertThat(reactivated.accountPlugin().getId()).isEqualTo(initial.accountPlugin().getId());
            assertThat(reactivated.accountPlugin().isActive()).isTrue();
            assertThat(reactivated.accountPlugin().getDeactivatedAt()).isNull();
            assertThat(reactivated.accountPlugin().getPluginData()).containsEntry("tenantId", "reactivated-tenant");
        }
    }

    @Nested
    @DisplayName("Deactivation")
    class Deactivation {

        @Test
        @DisplayName("Should deactivate active plugin")
        @Transactional
        void shouldDeactivateActivePlugin() {
            // Given
            Map<String, Object> pluginData = Map.of("tenantId", "test-tenant");
            pluginActivationService.activate(TEST_ACCOUNT_ID, BIT_BI_PLUGIN_ID, pluginData);

            // When
            pluginActivationService.deactivate(TEST_ACCOUNT_ID, BIT_BI_PLUGIN_ID);

            // Then
            AccountPlugin deactivated = accountPluginRepository
                    .findByAccountIdAndPluginId(TEST_ACCOUNT_ID, BIT_BI_PLUGIN_ID)
                    .orElseThrow();
            assertThat(deactivated.isActive()).isFalse();
            assertThat(deactivated.getDeactivatedAt()).isNotNull();
        }

        @Test
        @DisplayName("Should throw PluginNotActivatedException for unactivated plugin")
        void shouldThrowPluginNotActivatedExceptionForUnactivatedPlugin() {
            // Given - No activation exists for this account

            // When / Then
            assertThatThrownBy(() ->
                    pluginActivationService.deactivate(TEST_ACCOUNT_ID, BIT_BI_PLUGIN_ID))
                    .isInstanceOf(PluginNotActivatedException.class)
                    .hasMessageContaining(BIT_BI_PLUGIN_ID);
        }

        @Test
        @DisplayName("Should throw PluginNotActivatedException for already deactivated plugin")
        @Transactional
        void shouldThrowPluginNotActivatedExceptionForAlreadyDeactivatedPlugin() {
            // Given
            Map<String, Object> pluginData = Map.of("tenantId", "test-tenant");
            pluginActivationService.activate(TEST_ACCOUNT_ID, BIT_BI_PLUGIN_ID, pluginData);
            pluginActivationService.deactivate(TEST_ACCOUNT_ID, BIT_BI_PLUGIN_ID);

            // When / Then
            assertThatThrownBy(() ->
                    pluginActivationService.deactivate(TEST_ACCOUNT_ID, BIT_BI_PLUGIN_ID))
                    .isInstanceOf(PluginNotActivatedException.class)
                    .hasMessageContaining("already deactivated");
        }
    }

    @Nested
    @DisplayName("Concurrent Operations")
    class ConcurrentOperations {

        @Test
        @DisplayName("Should handle concurrent activation requests correctly")
        void shouldHandleConcurrentActivationRequestsCorrectly() throws InterruptedException {
            // Given
            int threadCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);

            // When - Multiple threads try to activate the same plugin
            for (int i = 0; i < threadCount; i++) {
                final int threadNum = i;
                executor.submit(() -> {
                    try {
                        Map<String, Object> pluginData = Map.of("tenantId", "concurrent-tenant-" + threadNum);
                        pluginActivationService.activate(TEST_ACCOUNT_ID, BIT_BI_PLUGIN_ID, pluginData);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            // Then - All requests should succeed (upsert behavior handles concurrency)
            // Note: Some requests may fail due to database concurrency, but at least one should succeed
            assertThat(successCount.get() + failureCount.get()).isEqualTo(threadCount);
            assertThat(successCount.get()).isGreaterThanOrEqualTo(1);

            // Verify final state
            AccountPlugin result = accountPluginRepository
                    .findByAccountIdAndPluginId(TEST_ACCOUNT_ID, BIT_BI_PLUGIN_ID)
                    .orElseThrow();
            assertThat(result.isActive()).isTrue();
        }
    }

    @Nested
    @DisplayName("Plugin Registry")
    class PluginRegistryTests {

        @Test
        @DisplayName("Should have bit-bi plugin registered")
        void shouldHaveBitBiPluginRegistered() {
            // When
            Plugin bitBiPlugin = pluginRegistry.findById(BIT_BI_PLUGIN_ID).orElse(null);

            // Then
            assertThat(bitBiPlugin).isNotNull();
            assertThat(bitBiPlugin.getName()).isEqualTo("Bit BI");
            assertThat(bitBiPlugin.getVersion()).isNotBlank();
            assertThat(bitBiPlugin.getSupportedEvents()).contains(PluginEventType.BATCH_COMPLETED);
        }

        @Test
        @DisplayName("Should validate plugin schema")
        void shouldValidatePluginSchema() {
            // When
            Plugin bitBiPlugin = pluginRegistry.findById(BIT_BI_PLUGIN_ID).orElseThrow();

            // Then
            assertThat(bitBiPlugin.getSchemaJson()).isNotBlank();
            assertThat(bitBiPlugin.getSchemaJson()).contains("tenantId");
            assertThat(bitBiPlugin.getSchemaJson()).contains("required");
        }
    }
}

package com.bitbi.dfm.plugin.unit;

import com.bitbi.dfm.plugin.domain.AccountPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for AccountPlugin domain entity.
 *
 * <p>Tests lifecycle methods:
 * <ul>
 *   <li>activate() sets correct timestamps</li>
 *   <li>deactivate() sets deactivated_at</li>
 *   <li>reactivate() clears deactivated_at</li>
 *   <li>updatePluginData() updates data and timestamp</li>
 *   <li>recordUsage() updates last_used_at</li>
 * </ul>
 *
 * @see AccountPlugin
 */
@DisplayName("AccountPlugin")
class AccountPluginTest {

    private UUID accountId;
    private String pluginId;
    private Map<String, Object> pluginData;

    @BeforeEach
    void setUp() {
        accountId = UUID.randomUUID();
        pluginId = "bit-bi";
        pluginData = Map.of("tenantId", "tenant-123");
    }

    @Nested
    @DisplayName("apiKeyLookup (031)")
    class ApiKeyLookupField {

        @Test
        @DisplayName("newly activated plugin should have no api key lookup")
        void shouldHaveNullLookupWhenActivated() {
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);

            assertThat(plugin.getApiKeyLookup()).isNull();
        }

        @Test
        @DisplayName("updateApiKeyLookup should store the lookup and bump updatedAt")
        void shouldStoreLookup() throws InterruptedException {
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);
            Instant initialUpdatedAt = plugin.getUpdatedAt();
            Thread.sleep(5);

            plugin.updateApiKeyLookup("a".repeat(64));

            assertThat(plugin.getApiKeyLookup()).isEqualTo("a".repeat(64));
            assertThat(plugin.getUpdatedAt()).isAfter(initialUpdatedAt);
        }

        @Test
        @DisplayName("updateApiKeyLookup should accept null to clear the lookup")
        void shouldClearLookup() {
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);
            plugin.updateApiKeyLookup("b".repeat(64));

            plugin.updateApiKeyLookup(null);

            assertThat(plugin.getApiKeyLookup()).isNull();
        }
    }

    @Nested
    @DisplayName("activate() static factory")
    class ActivateStaticFactory {

        @Test
        @DisplayName("should create AccountPlugin with correct fields")
        void shouldCreateAccountPluginWithCorrectFields() {
            // When
            AccountPlugin result = AccountPlugin.activate(accountId, pluginId, pluginData);

            // Then
            assertThat(result.getAccountId()).isEqualTo(accountId);
            assertThat(result.getPluginId()).isEqualTo(pluginId);
            assertThat(result.getPluginData()).containsEntry("tenantId", "tenant-123");
            assertThat(result.isActive()).isTrue();
        }

        @Test
        @DisplayName("should set activatedAt to current time")
        void shouldSetActivatedAtToCurrentTime() {
            // Given
            Instant before = Instant.now();

            // When
            AccountPlugin result = AccountPlugin.activate(accountId, pluginId, pluginData);

            // Then
            Instant after = Instant.now();
            assertThat(result.getActivatedAt())
                .isNotNull()
                .isAfterOrEqualTo(before)
                .isBeforeOrEqualTo(after);
        }

        @Test
        @DisplayName("should set createdAt and updatedAt to current time")
        void shouldSetCreatedAtAndUpdatedAtToCurrentTime() {
            // Given
            Instant before = Instant.now();

            // When
            AccountPlugin result = AccountPlugin.activate(accountId, pluginId, pluginData);

            // Then
            Instant after = Instant.now();
            assertThat(result.getCreatedAt())
                .isNotNull()
                .isAfterOrEqualTo(before)
                .isBeforeOrEqualTo(after);
            assertThat(result.getUpdatedAt())
                .isNotNull()
                .isAfterOrEqualTo(before)
                .isBeforeOrEqualTo(after);
        }

        @Test
        @DisplayName("should have null deactivatedAt and lastUsedAt")
        void shouldHaveNullDeactivatedAtAndLastUsedAt() {
            // When
            AccountPlugin result = AccountPlugin.activate(accountId, pluginId, pluginData);

            // Then
            assertThat(result.getDeactivatedAt()).isNull();
            assertThat(result.getLastUsedAt()).isNull();
        }

        @Test
        @DisplayName("should handle null pluginData")
        void shouldHandleNullPluginData() {
            // When
            AccountPlugin result = AccountPlugin.activate(accountId, pluginId, null);

            // Then
            assertThat(result.getPluginData()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("should create defensive copy of pluginData")
        void shouldCreateDefensiveCopyOfPluginData() {
            // Given
            Map<String, Object> mutableData = new HashMap<>();
            mutableData.put("tenantId", "original");

            // When
            AccountPlugin result = AccountPlugin.activate(accountId, pluginId, mutableData);
            mutableData.put("tenantId", "modified");

            // Then - original data should not be modified
            assertThat(result.getPluginData()).containsEntry("tenantId", "original");
        }
    }

    @Nested
    @DisplayName("deactivate()")
    class DeactivateMethod {

        @Test
        @DisplayName("should set isActive to false")
        void shouldSetIsActiveToFalse() {
            // Given
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);
            assertThat(plugin.isActive()).isTrue();

            // When
            plugin.deactivate();

            // Then
            assertThat(plugin.isActive()).isFalse();
        }

        @Test
        @DisplayName("should set deactivatedAt to current time")
        void shouldSetDeactivatedAtToCurrentTime() {
            // Given
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);
            Instant before = Instant.now();

            // When
            plugin.deactivate();

            // Then
            Instant after = Instant.now();
            assertThat(plugin.getDeactivatedAt())
                .isNotNull()
                .isAfterOrEqualTo(before)
                .isBeforeOrEqualTo(after);
        }

        @Test
        @DisplayName("should update updatedAt timestamp")
        void shouldUpdateUpdatedAtTimestamp() {
            // Given
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);
            Instant originalUpdatedAt = plugin.getUpdatedAt();

            // Small delay to ensure different timestamp
            try { Thread.sleep(1); } catch (InterruptedException e) { /* ignore */ }

            // When
            plugin.deactivate();

            // Then
            assertThat(plugin.getUpdatedAt()).isAfterOrEqualTo(originalUpdatedAt);
        }

        @Test
        @DisplayName("should preserve pluginData")
        void shouldPreservePluginData() {
            // Given
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);

            // When
            plugin.deactivate();

            // Then
            assertThat(plugin.getPluginData()).containsEntry("tenantId", "tenant-123");
        }
    }

    @Nested
    @DisplayName("reactivate()")
    class ReactivateMethod {

        @Test
        @DisplayName("should set isActive to true")
        void shouldSetIsActiveToTrue() {
            // Given
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);
            plugin.deactivate();
            assertThat(plugin.isActive()).isFalse();

            // When
            plugin.reactivate(Map.of("tenantId", "new-tenant"));

            // Then
            assertThat(plugin.isActive()).isTrue();
        }

        @Test
        @DisplayName("should clear deactivatedAt")
        void shouldClearDeactivatedAt() {
            // Given
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);
            plugin.deactivate();
            assertThat(plugin.getDeactivatedAt()).isNotNull();

            // When
            plugin.reactivate(Map.of("tenantId", "new-tenant"));

            // Then
            assertThat(plugin.getDeactivatedAt()).isNull();
        }

        @Test
        @DisplayName("should update pluginData with new data")
        void shouldUpdatePluginDataWithNewData() {
            // Given
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);
            plugin.deactivate();

            // When
            plugin.reactivate(Map.of("tenantId", "new-tenant-456"));

            // Then
            assertThat(plugin.getPluginData()).containsEntry("tenantId", "new-tenant-456");
        }

        @Test
        @DisplayName("should update updatedAt timestamp")
        void shouldUpdateUpdatedAtTimestamp() {
            // Given
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);
            plugin.deactivate();
            Instant afterDeactivation = plugin.getUpdatedAt();

            // Small delay
            try { Thread.sleep(1); } catch (InterruptedException e) { /* ignore */ }

            // When
            plugin.reactivate(Map.of("tenantId", "new-tenant"));

            // Then
            assertThat(plugin.getUpdatedAt()).isAfterOrEqualTo(afterDeactivation);
        }

        @Test
        @DisplayName("should preserve existing pluginData when null is passed (merge semantics)")
        void shouldPreserveExistingPluginDataWhenNullIsPassed() {
            // Given
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);
            plugin.deactivate();

            // When
            plugin.reactivate(null);

            // Then - merge semantics: null preserves existing data
            assertThat(plugin.getPluginData())
                .isNotNull()
                .containsEntry("tenantId", "tenant-123");
        }
    }

    @Nested
    @DisplayName("updatePluginData()")
    class UpdatePluginDataMethod {

        @Test
        @DisplayName("should update pluginData with new values")
        void shouldUpdatePluginDataWithNewValues() {
            // Given
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);

            // When
            plugin.updatePluginData(Map.of("tenantId", "updated-tenant"));

            // Then
            assertThat(plugin.getPluginData()).containsEntry("tenantId", "updated-tenant");
        }

        @Test
        @DisplayName("should update updatedAt timestamp")
        void shouldUpdateUpdatedAtTimestamp() {
            // Given
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);
            Instant originalUpdatedAt = plugin.getUpdatedAt();

            // Small delay
            try { Thread.sleep(1); } catch (InterruptedException e) { /* ignore */ }

            // When
            plugin.updatePluginData(Map.of("tenantId", "updated-tenant"));

            // Then
            assertThat(plugin.getUpdatedAt()).isAfterOrEqualTo(originalUpdatedAt);
        }

        @Test
        @DisplayName("should not change isActive status")
        void shouldNotChangeIsActiveStatus() {
            // Given
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);
            assertThat(plugin.isActive()).isTrue();

            // When
            plugin.updatePluginData(Map.of("tenantId", "updated-tenant"));

            // Then
            assertThat(plugin.isActive()).isTrue();
        }

        @Test
        @DisplayName("should preserve existing pluginData when null is passed (merge semantics)")
        void shouldPreserveExistingPluginDataWhenNullIsPassed() {
            // Given
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);

            // When
            plugin.updatePluginData(null);

            // Then - merge semantics: null preserves existing data
            assertThat(plugin.getPluginData())
                .isNotNull()
                .containsEntry("tenantId", "tenant-123");
        }

        @Test
        @DisplayName("should create defensive copy of new data")
        void shouldCreateDefensiveCopyOfNewData() {
            // Given
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);
            Map<String, Object> mutableData = new HashMap<>();
            mutableData.put("tenantId", "original");

            // When
            plugin.updatePluginData(mutableData);
            mutableData.put("tenantId", "modified");

            // Then - plugin data should not be modified
            assertThat(plugin.getPluginData()).containsEntry("tenantId", "original");
        }
    }

    @Nested
    @DisplayName("recordUsage()")
    class RecordUsageMethod {

        @Test
        @DisplayName("should set lastUsedAt to current time")
        void shouldSetLastUsedAtToCurrentTime() {
            // Given
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);
            assertThat(plugin.getLastUsedAt()).isNull();
            Instant before = Instant.now();

            // When
            plugin.recordUsage();

            // Then
            Instant after = Instant.now();
            assertThat(plugin.getLastUsedAt())
                .isNotNull()
                .isAfterOrEqualTo(before)
                .isBeforeOrEqualTo(after);
        }

        @Test
        @DisplayName("should update updatedAt timestamp")
        void shouldUpdateUpdatedAtTimestamp() {
            // Given
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);
            Instant originalUpdatedAt = plugin.getUpdatedAt();

            // Small delay
            try { Thread.sleep(1); } catch (InterruptedException e) { /* ignore */ }

            // When
            plugin.recordUsage();

            // Then
            assertThat(plugin.getUpdatedAt()).isAfterOrEqualTo(originalUpdatedAt);
        }

        @Test
        @DisplayName("should update lastUsedAt on subsequent calls")
        void shouldUpdateLastUsedAtOnSubsequentCalls() {
            // Given
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);
            plugin.recordUsage();
            Instant firstUsage = plugin.getLastUsedAt();

            // Small delay
            try { Thread.sleep(1); } catch (InterruptedException e) { /* ignore */ }

            // When
            plugin.recordUsage();

            // Then
            assertThat(plugin.getLastUsedAt()).isAfterOrEqualTo(firstUsage);
        }
    }

    @Nested
    @DisplayName("isActive()")
    class IsActiveMethod {

        @Test
        @DisplayName("should return true for newly activated plugin")
        void shouldReturnTrueForNewlyActivatedPlugin() {
            // When
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);

            // Then
            assertThat(plugin.isActive()).isTrue();
        }

        @Test
        @DisplayName("should return false for deactivated plugin")
        void shouldReturnFalseForDeactivatedPlugin() {
            // Given
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);

            // When
            plugin.deactivate();

            // Then
            assertThat(plugin.isActive()).isFalse();
        }

        @Test
        @DisplayName("should return true for reactivated plugin")
        void shouldReturnTrueForReactivatedPlugin() {
            // Given
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);
            plugin.deactivate();

            // When
            plugin.reactivate(pluginData);

            // Then
            assertThat(plugin.isActive()).isTrue();
        }
    }

    @Nested
    @DisplayName("Baseline Batch Methods")
    class BaselineBatchMethods {

        @Test
        @DisplayName("setBaselineBatchId should set the baseline batch ID")
        void shouldSetBaselineBatchId() {
            // Given
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);
            UUID batchId = UUID.randomUUID();

            // When
            plugin.setBaselineBatchId(batchId);

            // Then
            assertThat(plugin.getBaselineBatchId()).isEqualTo(batchId);
        }

        @Test
        @DisplayName("setBaselineBatchId should update updatedAt timestamp")
        void shouldUpdateUpdatedAtWhenSettingBaseline() {
            // Given
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);
            Instant originalUpdatedAt = plugin.getUpdatedAt();

            // Small delay
            try { Thread.sleep(1); } catch (InterruptedException e) { /* ignore */ }

            // When
            plugin.setBaselineBatchId(UUID.randomUUID());

            // Then
            assertThat(plugin.getUpdatedAt()).isAfterOrEqualTo(originalUpdatedAt);
        }

        @Test
        @DisplayName("setBaselineBatchId with null should clear baseline")
        void shouldClearBaselineWhenSetToNull() {
            // Given
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);
            plugin.setBaselineBatchId(UUID.randomUUID());

            // When
            plugin.setBaselineBatchId(null);

            // Then
            assertThat(plugin.getBaselineBatchId()).isNull();
        }

        @Test
        @DisplayName("hasBaselineBatch should return false when no baseline set")
        void shouldReturnFalseWhenNoBaselineSet() {
            // Given
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);

            // Then
            assertThat(plugin.hasBaselineBatch()).isFalse();
        }

        @Test
        @DisplayName("hasBaselineBatch should return true when baseline is set")
        void shouldReturnTrueWhenBaselineIsSet() {
            // Given
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);
            plugin.setBaselineBatchId(UUID.randomUUID());

            // Then
            assertThat(plugin.hasBaselineBatch()).isTrue();
        }

        @Test
        @DisplayName("isBaselineBatch should return true when batch matches baseline")
        void shouldReturnTrueWhenBatchMatchesBaseline() {
            // Given
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);
            UUID batchId = UUID.randomUUID();
            plugin.setBaselineBatchId(batchId);

            // Then
            assertThat(plugin.isBaselineBatch(batchId)).isTrue();
        }

        @Test
        @DisplayName("isBaselineBatch should return false when batch does not match baseline")
        void shouldReturnFalseWhenBatchDoesNotMatchBaseline() {
            // Given
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);
            UUID baselineBatchId = UUID.randomUUID();
            UUID otherBatchId = UUID.randomUUID();
            plugin.setBaselineBatchId(baselineBatchId);

            // Then
            assertThat(plugin.isBaselineBatch(otherBatchId)).isFalse();
        }

        @Test
        @DisplayName("isBaselineBatch should return false when no baseline set")
        void shouldReturnFalseWhenNoBaselineSetForComparison() {
            // Given
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);

            // Then
            assertThat(plugin.isBaselineBatch(UUID.randomUUID())).isFalse();
        }

        @Test
        @DisplayName("newly activated plugin should have null baseline batch")
        void shouldHaveNullBaselineWhenNewlyActivated() {
            // When
            AccountPlugin plugin = AccountPlugin.activate(accountId, pluginId, pluginData);

            // Then
            assertThat(plugin.getBaselineBatchId()).isNull();
            assertThat(plugin.hasBaselineBatch()).isFalse();
        }
    }
}

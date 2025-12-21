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
}

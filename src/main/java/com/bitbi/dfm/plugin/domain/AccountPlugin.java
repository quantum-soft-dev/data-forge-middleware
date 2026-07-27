package com.bitbi.dfm.plugin.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Entity representing an account's activation of a plugin.
 * Contains plugin-specific data (e.g., Bit BI's tenantId) and activation status.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>NOT EXISTS → ACTIVE: First activation creates record with is_active = true</li>
 *   <li>ACTIVE → INACTIVE: Deactivation sets is_active = false, deactivated_at = NOW()</li>
 *   <li>INACTIVE → ACTIVE: Reactivation sets is_active = true, deactivated_at = null</li>
 *   <li>ACTIVE → ACTIVE: Update sets plugin_data, updated_at = NOW() (upsert behavior)</li>
 * </ul>
 *
 * <p>Business Rules per FR-005:
 * <ul>
 *   <li>plugin_data must validate against the plugin's JSON Schema</li>
 *   <li>Upsert behavior: If (account_id, plugin_id) exists, update and set is_active = true</li>
 *   <li>last_used_at updated when plugin receives an event (FR-018)</li>
 * </ul>
 */
@Entity
@Table(name = "account_plugins")
@Getter
@NoArgsConstructor
public class AccountPlugin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "plugin_id", length = 64, nullable = false)
    private String pluginId;

    @Type(JsonBinaryType.class)
    @Column(name = "plugin_data", columnDefinition = "jsonb", nullable = false)
    @Getter(lombok.AccessLevel.NONE) // Custom getter returns immutable copy
    private Map<String, Object> pluginData;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "activated_at", nullable = false)
    private Instant activatedAt;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /**
     * The batch ID used as baseline for CSV file initialization.
     * SQL generation is skipped for this batch - clients should download
     * CSV files directly via the /sites/{siteId}/files endpoint.
     *
     * <p>Set when:
     * <ul>
     *   <li>Plugin activation: latest completed batch (or null if no batches exist)</li>
     *   <li>Plugin reinit: latest completed batch at reinit time</li>
     *   <li>First batch after activation: if null, the first incoming batch becomes baseline</li>
     * </ul>
     */
    @Column(name = "baseline_batch_id")
    private UUID baselineBatchId;

    /**
     * Indexed lookup handle for the plugin API key: hex SHA-256 of the raw key (031, V42).
     * <p>
     * Lets API key validation resolve the activation with a single indexed point query instead of
     * scanning every activation. Verification still runs against the BCrypt hash in
     * {@code plugin_data -> 'apiKeyHash'} — this column is only an index key.
     * </p>
     * <p>
     * Null for activations whose key was issued before V42: the plaintext is not stored, so it
     * cannot be backfilled. Those rows fall back to the legacy scan until the key is rotated.
     * </p>
     */
    @Column(name = "api_key_lookup", length = 64)
    private String apiKeyLookup;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Factory method to create a new active AccountPlugin.
     *
     * @param accountId the account activating the plugin
     * @param pluginId the plugin identifier
     * @param pluginData plugin-specific data (validated against schema)
     * @return new AccountPlugin instance
     */
    public static AccountPlugin activate(UUID accountId, String pluginId, Map<String, Object> pluginData) {
        AccountPlugin ap = new AccountPlugin();
        ap.accountId = accountId;
        ap.pluginId = pluginId;
        ap.pluginData = pluginData != null ? new HashMap<>(pluginData) : new HashMap<>();
        ap.active = true;
        ap.activatedAt = Instant.now();
        ap.createdAt = Instant.now();
        ap.updatedAt = Instant.now();
        return ap;
    }

    /**
     * Deactivates this plugin for the account.
     * Sets is_active = false and records deactivation timestamp.
     */
    public void deactivate() {
        this.active = false;
        this.deactivatedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * Reactivates a previously deactivated plugin.
     * Merges new plugin_data with existing data and clears deactivation timestamp.
     *
     * <p>Merge semantics (prevents data loss):
     * <ul>
     *   <li>New keys are added</li>
     *   <li>Existing keys are updated with new values</li>
     *   <li>Keys not in newPluginData are preserved</li>
     * </ul>
     *
     * @param newPluginData new plugin-specific data to merge
     */
    public void reactivate(Map<String, Object> newPluginData) {
        mergePluginData(newPluginData);
        this.active = true;
        this.deactivatedAt = null;
        this.updatedAt = Instant.now();
    }

    /**
     * Updates plugin data for an already active plugin (upsert behavior).
     *
     * <p>Merge semantics (prevents data loss):
     * <ul>
     *   <li>New keys are added</li>
     *   <li>Existing keys are updated with new values</li>
     *   <li>Keys not in newPluginData are preserved</li>
     * </ul>
     *
     * @param newPluginData new plugin-specific data to merge
     */
    public void updatePluginData(Map<String, Object> newPluginData) {
        mergePluginData(newPluginData);
        this.updatedAt = Instant.now();
    }

    /**
     * Removes a single key from plugin data.
     * <p>
     * {@link #updatePluginData(Map)} deliberately merges, so it can add and overwrite but never
     * delete — retiring a secret (e.g. the legacy plaintext {@code apiKey} superseded by
     * {@code apiKeyHash}) needs this explicit removal.
     * </p>
     *
     * @param key the plugin data key to drop
     */
    public void removePluginData(String key) {
        if (this.pluginData == null || !this.pluginData.containsKey(key)) {
            return;
        }
        this.pluginData.remove(key);
        this.updatedAt = Instant.now();
    }

    /**
     * Merges new plugin data into existing data.
     * Preserves existing keys not present in the new data.
     *
     * @param newPluginData data to merge (can be null)
     */
    private void mergePluginData(Map<String, Object> newPluginData) {
        if (newPluginData == null || newPluginData.isEmpty()) {
            return; // Nothing to merge, preserve existing data
        }
        if (this.pluginData == null) {
            this.pluginData = new HashMap<>();
        }
        // Merge: new values overwrite existing, but missing keys are preserved
        this.pluginData.putAll(newPluginData);
    }

    /**
     * Returns an immutable copy of the plugin data.
     * Prevents external mutation of internal state.
     *
     * @return unmodifiable view of plugin data
     */
    public Map<String, Object> getPluginData() {
        if (this.pluginData == null) {
            return Map.of();
        }
        return Map.copyOf(this.pluginData);
    }

    /**
     * Sets the indexed API key lookup handle (031).
     * Written whenever the API key is generated or rotated, alongside the BCrypt hash.
     *
     * @param apiKeyLookup hex SHA-256 of the raw API key, or null to clear it
     */
    public void updateApiKeyLookup(String apiKeyLookup) {
        this.apiKeyLookup = apiKeyLookup;
        this.updatedAt = Instant.now();
    }

    /**
     * Records that this plugin activation was used (received an event).
     * Updates last_used_at timestamp per FR-018.
     */
    public void recordUsage() {
        this.lastUsedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * Checks if this activation is currently active.
     * @return true if active
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Sets the baseline batch ID for CSV file initialization.
     * SQL generation will be skipped for this batch.
     *
     * @param baselineBatchId the batch ID to use as baseline, or null to clear
     */
    public void setBaselineBatchId(UUID baselineBatchId) {
        this.baselineBatchId = baselineBatchId;
        this.updatedAt = Instant.now();
    }

    /**
     * Checks if the given batch ID is the baseline batch.
     * @param batchId the batch ID to check
     * @return true if this is the baseline batch
     */
    public boolean isBaselineBatch(UUID batchId) {
        return baselineBatchId != null && baselineBatchId.equals(batchId);
    }

    /**
     * Checks if baseline batch is set.
     * @return true if baseline batch ID is set
     */
    public boolean hasBaselineBatch() {
        return baselineBatchId != null;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
        if (activatedAt == null) {
            activatedAt = Instant.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}

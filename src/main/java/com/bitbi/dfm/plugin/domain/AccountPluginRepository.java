package com.bitbi.dfm.plugin.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for AccountPlugin entities.
 */
public interface AccountPluginRepository {

    /**
     * Finds an account-plugin activation by account ID and plugin ID.
     * @param accountId the account identifier
     * @param pluginId the plugin identifier
     * @return the activation record if found
     */
    Optional<AccountPlugin> findByAccountIdAndPluginId(UUID accountId, String pluginId);

    /**
     * Finds all active plugin activations for an account.
     * @param accountId the account identifier
     * @return list of active plugin activations
     */
    List<AccountPlugin> findActiveByAccountId(UUID accountId);

    /**
     * Finds all plugin activations for an account (including inactive).
     * @param accountId the account identifier
     * @param pageable pagination parameters
     * @return page of plugin activations
     */
    Page<AccountPlugin> findByAccountId(UUID accountId, Pageable pageable);

    /**
     * Finds all plugin activations for an account with optional active filter.
     * @param accountId the account identifier
     * @param includeInactive whether to include inactive activations
     * @param pageable pagination parameters
     * @return page of plugin activations
     */
    Page<AccountPlugin> findByAccountId(UUID accountId, boolean includeInactive, Pageable pageable);

    /**
     * Finds all active plugin activations for a specific plugin.
     * Used for event dispatch to find all accounts subscribed to a plugin.
     * @param pluginId the plugin identifier
     * @return list of active activations for the plugin
     */
    List<AccountPlugin> findActiveByPluginId(String pluginId);

    /**
     * Direct lookup of an active activation by its stored plugin_data login
     * (Parquet Export Basic Auth, 028): one indexed point query instead of scanning
     * every activation. Backed by the V40 unique expression index.
     */
    Optional<AccountPlugin> findActiveByPluginIdAndLogin(String pluginId, String login);

    /**
     * Direct lookup of an active activation by its API key lookup handle
     * (Bit BI Plugin API, 031): one indexed point query — the hex SHA-256 of the raw key —
     * instead of a BCrypt scan over every activation. Backed by the V42 unique partial index.
     *
     * @param pluginId the plugin identifier
     * @param apiKeyLookup hex SHA-256 of the raw API key
     * @return the active activation holding that key, if any
     */
    Optional<AccountPlugin> findActiveByPluginIdAndApiKeyLookup(String pluginId, String apiKeyLookup);

    /**
     * Active activations whose API key predates the lookup column (031) and therefore cannot be
     * resolved by point query. Only these rows need the legacy fallback scan; the set drains to
     * empty as keys are rotated, at which point the fallback can be deleted.
     *
     * @param pluginId the plugin identifier
     * @return active activations with a null api_key_lookup
     */
    List<AccountPlugin> findActiveByPluginIdWithoutApiKeyLookup(String pluginId);

    /**
     * Checks if an active activation exists for the account-plugin pair.
     * @param accountId the account identifier
     * @param pluginId the plugin identifier
     * @return true if active activation exists
     */
    boolean existsActiveByAccountIdAndPluginId(UUID accountId, String pluginId);

    /**
     * Saves an account-plugin activation.
     * @param accountPlugin the activation to save
     * @return the saved activation
     */
    AccountPlugin save(AccountPlugin accountPlugin);

    /**
     * Deletes an account-plugin activation (hard delete - use deactivate() for soft delete).
     * @param accountPlugin the activation to delete
     */
    void delete(AccountPlugin accountPlugin);

    /**
     * Finds an account plugin by ID.
     * @param id the account plugin ID
     * @return the activation record if found
     */
    Optional<AccountPlugin> findById(Long id);

    /**
     * Finds all account-plugin activations with pagination.
     * Used for admin listing of all plugin activations.
     * @param pageable pagination parameters
     * @return page of all account-plugin activations
     */
    Page<AccountPlugin> findAll(Pageable pageable);

    /**
     * Finds all active account-plugin activations by plugin ID with pagination.
     * @param pluginId the plugin identifier
     * @param pageable pagination parameters
     * @return page of active plugin activations for the specified plugin
     */
    Page<AccountPlugin> findByPluginIdAndActiveTrue(String pluginId, Pageable pageable);

    /**
     * Updates only the last_used_at timestamp for an account-plugin activation.
     * Uses atomic UPDATE query to avoid overwriting concurrent changes to other fields
     * (e.g., baseline_batch_id set by SqlGenerationService).
     *
     * @param id the account plugin ID
     */
    void updateLastUsedAtById(Long id);

    /**
     * Null every {@code baseline_batch_id} pointing at a batch of the site (issue #89 — history
     * wipe). The FK is {@code ON DELETE RESTRICT}, so a wipe cannot delete the batches otherwise.
     * <p>
     * Nulled, not re-pointed: the next completed batch of the account becomes the baseline by
     * itself, which is exactly the semantics of a reinit performed with no batches in hand.
     * </p>
     *
     * @param siteId site identifier
     * @return number of activations detached
     */
    int detachBaselineBatchesOfSite(UUID siteId);
}

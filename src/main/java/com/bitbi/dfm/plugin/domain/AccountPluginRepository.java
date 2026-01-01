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
     * Finds an active account plugin by plugin ID and API key.
     * Uses JSONB containment query for performance.
     *
     * @param pluginId the plugin identifier
     * @param apiKey the API key value
     * @return the activation record if found and active
     */
    Optional<AccountPlugin> findByPluginIdAndApiKey(String pluginId, String apiKey);

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
}

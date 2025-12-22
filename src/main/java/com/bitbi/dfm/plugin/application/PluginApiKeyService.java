package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginApiKey;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Service for managing Plugin API Keys for Bit BI Plugin API authentication.
 * <p>
 * API Keys are stored in account_plugins.plugin_data JSONB field.
 * </p>
 */
@Service
public class PluginApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(PluginApiKeyService.class);
    private static final String API_KEY_FIELD = "apiKey";
    private static final String PLUGIN_ID = "bit-bi";

    private final AccountPluginRepository accountPluginRepository;
    private final MeterRegistry meterRegistry;

    public PluginApiKeyService(
            AccountPluginRepository accountPluginRepository,
            MeterRegistry meterRegistry) {
        this.accountPluginRepository = accountPluginRepository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Generates and stores a new API Key for an account's Bit BI plugin activation.
     * If an API Key already exists, it will be replaced (rotated).
     *
     * @param accountPluginId The ID of the account plugin activation
     * @return The generated API Key
     */
    @Transactional
    public PluginApiKey generateApiKey(Long accountPluginId) {
        AccountPlugin accountPlugin = accountPluginRepository.findById(accountPluginId)
                .orElseThrow(() -> new IllegalArgumentException("AccountPlugin not found: " + accountPluginId));

        PluginApiKey apiKey = PluginApiKey.generate();

        // Store API key in plugin_data
        Map<String, Object> updatedData = new HashMap<>(accountPlugin.getPluginData());
        updatedData.put(API_KEY_FIELD, apiKey.value());
        accountPlugin.updatePluginData(updatedData);

        accountPluginRepository.save(accountPlugin);

        log.info("Generated API Key for account plugin: id={}, key={}",
                accountPluginId, apiKey); // toString() returns masked key

        return apiKey;
    }

    /**
     * Generates and stores a new API Key for an account's Bit BI plugin.
     *
     * @param accountId The account ID
     * @return The generated API Key
     */
    @Transactional
    public PluginApiKey generateApiKeyForAccount(UUID accountId) {
        AccountPlugin accountPlugin = accountPluginRepository
                .findByAccountIdAndPluginId(accountId, PLUGIN_ID)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No active Bit BI plugin for account: " + accountId));

        return generateApiKey(accountPlugin.getId());
    }

    /**
     * Validates an API Key and returns the associated AccountPlugin if valid.
     * Performance requirement: <50ms (SC-004).
     *
     * @param apiKeyValue The raw API key value
     * @return Optional containing the AccountPlugin if valid and active
     */
    @Transactional(readOnly = true)
    public Optional<AccountPlugin> validateApiKey(String apiKeyValue) {
        Timer.Sample timer = Timer.start(meterRegistry);

        try {
            // Fast path: Check format first
            if (!PluginApiKey.isValid(apiKeyValue)) {
                log.debug("Invalid API key format");
                meterRegistry.counter("plugin.api.key.validation.invalid.format").increment();
                return Optional.empty();
            }

            // Query for account plugin with this API key
            // Uses JSONB containment query for performance
            Optional<AccountPlugin> result = accountPluginRepository
                    .findByPluginIdAndApiKey(PLUGIN_ID, apiKeyValue);

            if (result.isEmpty()) {
                log.debug("API key not found in any account plugin");
                meterRegistry.counter("plugin.api.key.validation.not.found").increment();
                return Optional.empty();
            }

            AccountPlugin accountPlugin = result.get();
            if (!accountPlugin.isActive()) {
                log.debug("API key belongs to inactive plugin: accountId={}",
                        accountPlugin.getAccountId());
                meterRegistry.counter("plugin.api.key.validation.inactive").increment();
                return Optional.empty();
            }

            meterRegistry.counter("plugin.api.key.validation.success").increment();
            return Optional.of(accountPlugin);

        } finally {
            timer.stop(meterRegistry.timer("plugin.api.key.validation.duration"));
        }
    }

    /**
     * Gets the API Key for an account's Bit BI plugin (if exists).
     *
     * @param accountId The account ID
     * @return Optional containing the API key if exists
     */
    @Transactional(readOnly = true)
    public Optional<PluginApiKey> getApiKey(UUID accountId) {
        return accountPluginRepository
                .findByAccountIdAndPluginId(accountId, PLUGIN_ID)
                .map(ap -> ap.getPluginData().get(API_KEY_FIELD))
                .filter(key -> key instanceof String)
                .map(key -> (String) key)
                .filter(PluginApiKey::isValid)
                .map(PluginApiKey::of);
    }

    /**
     * Rotates (regenerates) the API Key for an account's Bit BI plugin.
     * Invalidates the old key immediately.
     *
     * @param accountId The account ID
     * @return The new API Key
     */
    @Transactional
    public PluginApiKey rotateApiKey(UUID accountId) {
        log.info("Rotating API Key for account: {}", accountId);
        return generateApiKeyForAccount(accountId);
    }
}

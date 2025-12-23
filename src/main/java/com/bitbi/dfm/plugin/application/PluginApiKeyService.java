package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginApiKey;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Service for managing Plugin API Keys for Bit BI Plugin API authentication.
 * <p>
 * API Keys are stored as BCrypt hashes in account_plugins.plugin_data JSONB field.
 * The raw key is returned only once during generation - it cannot be retrieved later.
 * </p>
 * <p>
 * Security: Uses BCrypt hashing to protect API keys at rest. Even if the database
 * is compromised, the actual API keys cannot be recovered.
 * </p>
 */
@Service
public class PluginApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(PluginApiKeyService.class);
    private static final String API_KEY_HASH_FIELD = "apiKeyHash";
    private static final String PLUGIN_ID = "bit-bi";

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

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
     * <p>
     * Security: Only the BCrypt hash is stored in the database. The raw key is returned
     * only once and cannot be retrieved later. Users must save it securely.
     * </p>
     *
     * @param accountPluginId The ID of the account plugin activation
     * @return The generated API Key (raw value - store securely, cannot be retrieved again)
     */
    @Transactional
    public PluginApiKey generateApiKey(Long accountPluginId) {
        AccountPlugin accountPlugin = accountPluginRepository.findById(accountPluginId)
                .orElseThrow(() -> new IllegalArgumentException("AccountPlugin not found: " + accountPluginId));

        PluginApiKey apiKey = PluginApiKey.generate();

        // Security: Store BCrypt hash of API key, not the raw key
        String apiKeyHash = passwordEncoder.encode(apiKey.value());

        Map<String, Object> updatedData = new HashMap<>(accountPlugin.getPluginData());
        updatedData.put(API_KEY_HASH_FIELD, apiKeyHash);
        // Remove old plain-text key if exists (migration from old format)
        updatedData.remove("apiKey");
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
     * <p>
     * Security: Uses BCrypt comparison which is intentionally slow (~100ms) to prevent
     * timing attacks. For better performance with many accounts, consider caching or
     * adding an indexed key prefix lookup.
     * </p>
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

            // Load all active bit-bi plugin activations and compare BCrypt hashes
            // Note: This is O(n) where n = active accounts, but BCrypt prevents timing attacks
            List<AccountPlugin> activePlugins = accountPluginRepository.findActiveByPluginId(PLUGIN_ID);

            for (AccountPlugin accountPlugin : activePlugins) {
                String storedHash = getStoredApiKeyHash(accountPlugin);
                if (storedHash != null && passwordEncoder.matches(apiKeyValue, storedHash)) {
                    log.debug("API key validated for accountId={}", accountPlugin.getAccountId());
                    meterRegistry.counter("plugin.api.key.validation.success").increment();
                    return Optional.of(accountPlugin);
                }
            }

            log.debug("API key not found or does not match any account plugin");
            meterRegistry.counter("plugin.api.key.validation.not.found").increment();
            return Optional.empty();

        } finally {
            timer.stop(meterRegistry.timer("plugin.api.key.validation.duration"));
        }
    }

    /**
     * Extracts the stored API key hash from plugin data.
     * Supports both new format (apiKeyHash) and legacy format (apiKey) for migration.
     */
    private String getStoredApiKeyHash(AccountPlugin accountPlugin) {
        Map<String, Object> pluginData = accountPlugin.getPluginData();
        if (pluginData == null) {
            return null;
        }

        // Try new hash field first
        Object hashValue = pluginData.get(API_KEY_HASH_FIELD);
        if (hashValue instanceof String) {
            return (String) hashValue;
        }

        // Legacy support: If old plain-text key exists, hash it on-the-fly for comparison
        // This allows existing keys to work during migration
        Object legacyKey = pluginData.get("apiKey");
        if (legacyKey instanceof String legacyKeyStr && PluginApiKey.isValid(legacyKeyStr)) {
            log.warn("Legacy plain-text API key found for accountId={}. Please rotate the key.",
                    accountPlugin.getAccountId());
            // For legacy keys, we compare directly (not ideal but maintains compatibility)
            // The key will be hashed on next rotation
            return passwordEncoder.encode(legacyKeyStr);
        }

        return null;
    }

    /**
     * Checks if an API Key exists for an account's Bit BI plugin.
     * <p>
     * Security: Since API keys are stored as BCrypt hashes, the actual key cannot
     * be retrieved. Use this method to check if a key exists, and rotateApiKey()
     * to generate a new one if needed.
     * </p>
     *
     * @param accountId The account ID
     * @return true if an API key hash exists for the account
     */
    @Transactional(readOnly = true)
    public boolean hasApiKey(UUID accountId) {
        return accountPluginRepository
                .findByAccountIdAndPluginId(accountId, PLUGIN_ID)
                .map(ap -> ap.getPluginData().get(API_KEY_HASH_FIELD) != null
                        || ap.getPluginData().get("apiKey") != null) // Legacy support
                .orElse(false);
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

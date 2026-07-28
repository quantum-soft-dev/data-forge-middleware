package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginActionType;
import com.bitbi.dfm.plugin.domain.PluginApiKey;
import com.bitbi.dfm.plugin.domain.PluginCredentialRotatedEvent;
import com.bitbi.dfm.plugin.domain.exception.PluginNotActivatedException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Service for managing Plugin API Keys for Bit BI Plugin API authentication.
 * <p>
 * API Keys are stored twice, as two derivations of the same secret: the BCrypt hash in
 * {@code account_plugins.plugin_data -> 'apiKeyHash'} (verification) and the hex SHA-256 in
 * {@code account_plugins.api_key_lookup} (index handle, V42). The raw key is returned only once
 * during generation - it cannot be retrieved later.
 * </p>
 * <p>
 * Security: BCrypt protects the keys at rest — even with the database, the actual API keys cannot
 * be recovered. The SHA-256 lookup selects a candidate row and is never trusted on its own; it is
 * safe unsalted because the key carries ~190 bits of {@link java.security.SecureRandom} entropy.
 * </p>
 * <p>
 * Cost: one indexed query plus one BCrypt comparison per validated request, regardless of how many
 * accounts have the plugin active. Activations issued before V42 have no lookup handle and are
 * resolved by a fallback scan, tracked by {@code plugin.api.key.validation.legacy.scan} and
 * {@code plugin.api.key.validation.legacy.hit}.
 * </p>
 */
@Service
public class PluginApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(PluginApiKeyService.class);
    private static final String API_KEY_HASH_FIELD = "apiKeyHash";
    private static final String LEGACY_API_KEY_FIELD = "apiKey";
    private static final String PLUGIN_ID = "bit-bi";

    /**
     * Requests that fell through to the pre-V42 scan because the indexed lookup missed.
     * Non-zero means unindexed activations still exist (or someone is probing with bad keys).
     */
    private static final String LEGACY_SCAN_METRIC = "plugin.api.key.validation.legacy.scan";

    /**
     * Requests actually authenticated by the pre-V42 scan. Once this stays at zero for longer
     * than the longest plausible client key lifetime, the fallback path can be deleted.
     */
    private static final String LEGACY_HIT_METRIC = "plugin.api.key.validation.legacy.hit";

    private final AccountPluginRepository accountPluginRepository;
    private final MeterRegistry meterRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public PluginApiKeyService(
            AccountPluginRepository accountPluginRepository,
            MeterRegistry meterRegistry,
            ApplicationEventPublisher eventPublisher) {
        this(accountPluginRepository, meterRegistry, eventPublisher, new BCryptPasswordEncoder());
    }

    /**
     * Visible for testing: lets a test observe how many BCrypt comparisons a validation costs.
     */
    PluginApiKeyService(
            AccountPluginRepository accountPluginRepository,
            MeterRegistry meterRegistry,
            ApplicationEventPublisher eventPublisher,
            PasswordEncoder passwordEncoder) {
        this.accountPluginRepository = accountPluginRepository;
        this.meterRegistry = meterRegistry;
        this.eventPublisher = eventPublisher;
        this.passwordEncoder = passwordEncoder;
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

        return generateApiKeyFor(accountPlugin);
    }

    /**
     * Issues a new key for an already loaded activation, replacing any previous one.
     * <p>
     * Writes two derivations of the same key: the BCrypt hash (verification) and the SHA-256
     * lookup handle (index). They must always be written together — a hash without a lookup
     * degrades the activation to the legacy fallback scan.
     * </p>
     */
    private PluginApiKey generateApiKeyFor(AccountPlugin accountPlugin) {
        PluginApiKey apiKey = PluginApiKey.generate();

        // Security: Store BCrypt hash of API key, not the raw key
        String apiKeyHash = passwordEncoder.encode(apiKey.value());

        accountPlugin.updatePluginData(Map.of(API_KEY_HASH_FIELD, apiKeyHash));
        // Drop the old plain-text key if present (migration from the pre-hash format).
        // updatePluginData() merges, so the removal has to be explicit.
        accountPlugin.removePluginData(LEGACY_API_KEY_FIELD);
        accountPlugin.updateApiKeyLookup(apiKey.lookup());

        accountPluginRepository.save(accountPlugin);

        log.info("Generated API Key for account plugin: id={}, key={}",
                accountPlugin.getId(), apiKey); // toString() returns masked key

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

        return generateApiKeyFor(accountPlugin);
    }

    /**
     * Validates an API Key and returns the associated AccountPlugin if valid.
     * <p>
     * Cost: one indexed point query on {@code api_key_lookup} (hex SHA-256 of the key, V42) plus
     * at most one BCrypt comparison — independent of how many accounts have the plugin active.
     * BCrypt itself is deliberately slow (~100 ms at strength 10), and that single comparison
     * dominates the wall time; this method is therefore not, and cannot be, a sub-50ms operation.
     * What it does guarantee is constant cost: a malformed key never reaches the database, and an
     * unknown well-formed key costs no BCrypt at all, so unauthenticated traffic cannot amplify
     * into O(n) hashing.
     * </p>
     * <p>
     * Security: the SHA-256 lookup only selects the candidate row; verification still runs against
     * the BCrypt hash. Activations issued before V42 have no lookup and fall back to a scan over
     * that (shrinking) subset — see {@link #validateAgainstLegacyActivations}.
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

            // Indexed point query + exactly one BCrypt comparison.
            Optional<AccountPlugin> matched = accountPluginRepository
                    .findActiveByPluginIdAndApiKeyLookup(PLUGIN_ID, PluginApiKey.lookupOf(apiKeyValue))
                    .filter(AccountPlugin::isActive)
                    .filter(accountPlugin -> accountPlugin.getPluginData()
                            .get(API_KEY_HASH_FIELD) instanceof String hash
                            && passwordEncoder.matches(apiKeyValue, hash));

            if (matched.isEmpty()) {
                matched = validateAgainstLegacyActivations(apiKeyValue);
            }

            if (matched.isPresent()) {
                log.debug("API key validated for accountId={}", matched.get().getAccountId());
                meterRegistry.counter("plugin.api.key.validation.success").increment();
                return matched;
            }

            log.debug("API key not found or does not match any account plugin");
            meterRegistry.counter("plugin.api.key.validation.not.found").increment();
            return Optional.empty();

        } finally {
            timer.stop(meterRegistry.timer("plugin.api.key.validation.duration"));
        }
    }

    /**
     * Fallback for activations whose key was issued before the lookup column existed (V42): the
     * plaintext is not stored, so their lookup cannot be backfilled and they can only be found by
     * scanning. The scan is restricted to rows with a null lookup, so it drains to nothing as keys
     * are rotated — watch {@code plugin.api.key.validation.legacy.hit} and delete this path once it
     * stays at zero.
     */
    private Optional<AccountPlugin> validateAgainstLegacyActivations(String apiKeyValue) {
        List<AccountPlugin> legacyActivations =
                accountPluginRepository.findActiveByPluginIdWithoutApiKeyLookup(PLUGIN_ID);
        if (legacyActivations.isEmpty()) {
            return Optional.empty();
        }

        // Counts requests that still pay for a scan (n = legacy rows), i.e. the residual cost.
        meterRegistry.counter(LEGACY_SCAN_METRIC).increment();

        for (AccountPlugin accountPlugin : legacyActivations) {
            if (legacyKeyMatches(accountPlugin, apiKeyValue)) {
                // Counts keys that would break if this fallback were removed today.
                meterRegistry.counter(LEGACY_HIT_METRIC).increment();
                log.info("API key validated through the pre-V42 fallback for accountId={}; "
                        + "rotate the key to move it onto the indexed path", accountPlugin.getAccountId());
                return Optional.of(accountPlugin);
            }
        }
        return Optional.empty();
    }

    /**
     * Compares a candidate key against a pre-V42 activation.
     * <p>
     * Hashed keys use BCrypt. Plaintext keys (the oldest format) are compared byte-wise in
     * constant time — the previous implementation called {@code passwordEncoder.encode()} on the
     * stored plaintext and fed the result to {@code matches()}, which, because of the random salt,
     * is nothing but a ~100 ms string equality check run on every read.
     * </p>
     */
    private boolean legacyKeyMatches(AccountPlugin accountPlugin, String apiKeyValue) {
        Map<String, Object> pluginData = accountPlugin.getPluginData();

        if (pluginData.get(API_KEY_HASH_FIELD) instanceof String storedHash) {
            return passwordEncoder.matches(apiKeyValue, storedHash);
        }

        if (pluginData.get(LEGACY_API_KEY_FIELD) instanceof String storedPlaintext) {
            log.warn("Legacy plain-text API key found for accountId={}. Please rotate the key.",
                    accountPlugin.getAccountId());
            return MessageDigest.isEqual(
                    storedPlaintext.getBytes(StandardCharsets.UTF_8),
                    apiKeyValue.getBytes(StandardCharsets.UTF_8));
        }

        return false;
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
                        || ap.getPluginData().get(LEGACY_API_KEY_FIELD) != null) // Legacy support
                .orElse(false);
    }

    /**
     * Rotates (regenerates) the API Key for an account's Bit BI plugin.
     * Invalidates the old key immediately.
     * <p>
     * Rotation re-derives the SHA-256 lookup handle along with the BCrypt hash, so a rotated
     * activation always leaves the pre-V42 fallback scan. This is the path that lets
     * {@code plugin.api.key.validation.legacy.hit} reach zero and the legacy branch be deleted.
     * </p>
     *
     * @param accountId The account ID
     * @return The new API Key (raw value - store securely, cannot be retrieved again)
     * @throws PluginNotActivatedException if the account has no active Bit BI activation
     */
    @Transactional
    public PluginApiKey rotateApiKey(UUID accountId) {
        AccountPlugin accountPlugin = accountPluginRepository
                .findByAccountIdAndPluginId(accountId, PLUGIN_ID)
                .filter(AccountPlugin::isActive)
                .orElseThrow(() -> new PluginNotActivatedException(PLUGIN_ID, accountId));

        PluginApiKey apiKey = generateApiKeyFor(accountPlugin);

        // Audited by an AFTER_COMMIT listener: a rotation that rolls back must leave no trace
        // claiming it happened.
        eventPublisher.publishEvent(
                new PluginCredentialRotatedEvent(PLUGIN_ID, accountId, PluginActionType.API_KEY_ROTATED));
        log.info("Rotated API Key for account: {}", accountId);
        return apiKey;
    }
}

package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginCredentialRotatedEvent;
import com.bitbi.dfm.plugin.domain.PluginActionType;
import com.bitbi.dfm.plugin.domain.ParquetExportCredentials;
import com.bitbi.dfm.plugin.domain.exception.PluginNotActivatedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Basic Auth credential lifecycle for the Parquet Export plugin (028).
 * <p>
 * Storage reuses the Bit BI JSONB pattern ({@link PluginApiKeyService}): the login is kept in
 * plain form under {@code login}, the password only as a BCrypt hash under {@code passwordHash}.
 * Validation resolves the activation by login first, so each request costs exactly one BCrypt
 * comparison regardless of how many accounts have the plugin active.
 * </p>
 */
@Service
public class ParquetExportCredentialsService {

    public static final String PLUGIN_ID = "parquet-export";
    static final String LOGIN_FIELD = "login";
    static final String PASSWORD_HASH_FIELD = "passwordHash";

    private static final Logger log = LoggerFactory.getLogger(ParquetExportCredentialsService.class);

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private final AccountPluginRepository accountPluginRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ParquetExportCredentialsService(AccountPluginRepository accountPluginRepository,
                                           ApplicationEventPublisher eventPublisher) {
        this.accountPluginRepository = accountPluginRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Mint credentials for an activation that has none yet. Idempotent: returns empty (and leaves
     * plugin data untouched) when a login already exists, so re-activations never silently rotate.
     *
     * @return the freshly minted credentials — the only place the raw password ever exists
     */
    @Transactional
    public Optional<ParquetExportCredentials> mintCredentials(AccountPlugin accountPlugin) {
        if (accountPlugin.getPluginData().get(LOGIN_FIELD) != null) {
            log.debug("Credentials already exist for accountId={}, skipping mint", accountPlugin.getAccountId());
            return Optional.empty();
        }

        ParquetExportCredentials credentials = ParquetExportCredentials.generate();
        accountPlugin.updatePluginData(Map.of(
                LOGIN_FIELD, credentials.login(),
                PASSWORD_HASH_FIELD, passwordEncoder.encode(credentials.password())));
        accountPluginRepository.save(accountPlugin);

        log.info("Minted parquet-export credentials for accountId={}, credentials={}",
                accountPlugin.getAccountId(), credentials); // toString() masks the password
        return Optional.of(credentials);
    }

    /**
     * Rotate the password of an active activation: the login stays stable, the old password stops
     * authenticating immediately.
     *
     * @return credentials with the existing login and the new raw password (shown once)
     * @throws PluginNotActivatedException when the plugin is absent or inactive for the account
     */
    @Transactional
    public ParquetExportCredentials rotatePassword(UUID accountId) {
        AccountPlugin accountPlugin = accountPluginRepository
                .findByAccountIdAndPluginId(accountId, PLUGIN_ID)
                .filter(AccountPlugin::isActive)
                .orElseThrow(() -> new PluginNotActivatedException(PLUGIN_ID, accountId));

        String login = (String) accountPlugin.getPluginData().get(LOGIN_FIELD);
        if (login == null) {
            // Activation predates credential minting (should not happen) — backfill a login.
            login = ParquetExportCredentials.generateLogin();
        }

        ParquetExportCredentials credentials =
                new ParquetExportCredentials(login, ParquetExportCredentials.generatePassword());
        accountPlugin.updatePluginData(Map.of(
                LOGIN_FIELD, credentials.login(),
                PASSWORD_HASH_FIELD, passwordEncoder.encode(credentials.password())));
        accountPluginRepository.save(accountPlugin);

        // Audited AFTER_COMMIT — see PluginCredentialRotatedEvent.
        eventPublisher.publishEvent(
                new PluginCredentialRotatedEvent(PLUGIN_ID, accountId, PluginActionType.PASSWORD_ROTATED));
        log.info("Rotated parquet-export password for accountId={}", accountId);
        return credentials;
    }

    /**
     * Validate Basic Auth credentials: one indexed point query by login (unique expression
     * index, V40) followed by exactly one BCrypt comparison — request cost stays O(1) in the
     * number of activations, so unauthenticated garbage logins cannot force full scans.
     *
     * @return the matching active activation, or empty on any mismatch
     */
    @Transactional(readOnly = true)
    public Optional<AccountPlugin> validate(String login, String rawPassword) {
        if (login == null || rawPassword == null) {
            return Optional.empty();
        }

        return accountPluginRepository.findActiveByPluginIdAndLogin(PLUGIN_ID, login)
                .filter(accountPlugin ->
                        accountPlugin.getPluginData().get(PASSWORD_HASH_FIELD) instanceof String hash
                                && passwordEncoder.matches(rawPassword, hash));
    }
}

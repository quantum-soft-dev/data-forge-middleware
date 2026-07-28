package com.bitbi.dfm.integration;

import com.bitbi.dfm.account.domain.Account;
import com.bitbi.dfm.account.domain.AccountRepository;
import com.bitbi.dfm.plugin.application.PluginApiKeyService;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginActionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

/**
 * A rotation may only be audited if it actually happened.
 * <p>
 * The audit write runs {@code @Async} on a separate bean, in its own transaction. Called directly
 * from inside {@code rotateApiKey()}, it commits independently of the rotation it describes: if the
 * caller's transaction later rolls back, the audit trail permanently claims a security-sensitive
 * key rotation that never took effect. Auditing a rotation that did not happen is worse than not
 * auditing it — it is evidence pointing the wrong way.
 * </p>
 */
class PluginApiKeyRotationAuditIntegrationTest extends AbstractIntegrationTest {

    private static final String PLUGIN_ID = "bit-bi";

    @Autowired
    private PluginApiKeyService pluginApiKeyService;

    @Autowired
    private AccountPluginRepository accountPluginRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        // account_plugins carries an FK to accounts, so the activation needs a real owner.
        Account account = accountRepository.save(
                Account.create("api-key-rotation-audit@example.com", "Rotation Audit", null, null));
        accountId = account.getId();
        accountPluginRepository.save(AccountPlugin.activate(accountId, PLUGIN_ID, Map.of()));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM plugin_audit_logs WHERE account_id = ?", accountId);
        accountPluginRepository.findByAccountIdAndPluginId(accountId, PLUGIN_ID)
                .ifPresent(accountPluginRepository::delete);
        accountRepository.deleteById(accountId);
    }

    private long auditRows() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM plugin_audit_logs WHERE account_id = ? AND action_type = ?",
                Long.class, accountId, PluginActionType.API_KEY_ROTATED.name());
        return count == null ? 0L : count;
    }

    @Test
    @DisplayName("a committed rotation is audited")
    void shouldAuditACommittedRotation() {
        pluginApiKeyService.rotateApiKey(accountId);

        await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(auditRows()).isEqualTo(1L));
    }

    @Test
    @DisplayName("a rolled back rotation is not audited")
    void shouldNotAuditARolledBackRotation() {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            pluginApiKeyService.rotateApiKey(accountId);
            throw new IllegalStateException("caller fails after rotating");
        })).isInstanceOf(IllegalStateException.class);

        // Give an out-of-band async write every chance to land before concluding it did not.
        await().during(ofSeconds(2)).atMost(ofSeconds(5))
                .untilAsserted(() -> assertThat(auditRows())
                        .as("the rotation was rolled back, so nothing may claim it happened")
                        .isZero());

        assertThat(accountPluginRepository.findByAccountIdAndPluginId(accountId, PLUGIN_ID))
                .get()
                .extracting(AccountPlugin::getApiKeyLookup)
                .as("the rollback must also have undone the key itself")
                .isNull();
    }
}

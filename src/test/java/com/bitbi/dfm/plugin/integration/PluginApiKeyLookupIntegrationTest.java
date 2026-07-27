package com.bitbi.dfm.plugin.integration;

import com.bitbi.dfm.integration.AbstractIntegrationTest;
import com.bitbi.dfm.plugin.application.PluginApiKeyService;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginApiKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end checks for the indexed API key lookup (031) against a real PostgreSQL: the V42
 * column, the JPQL point query and the pre-V42 fallback all have to behave on actual rows —
 * the unit tests mock the repository and therefore cannot see a broken mapping or query.
 */
@DisplayName("Plugin API Key Lookup Integration Tests")
@Sql("/test-data.sql")
class PluginApiKeyLookupIntegrationTest extends AbstractIntegrationTest {

    private static final UUID TEST_ACCOUNT_ID = UUID.fromString("0199baac-f851-7ed9-5963-00dbaf07b233");
    private static final UUID SECOND_ACCOUNT_ID = UUID.fromString("0199bab1-fad2-bf76-c478-eae1f61e1c17");
    private static final String PLUGIN_ID = "bit-bi";

    @Autowired
    private PluginApiKeyService pluginApiKeyService;

    @Autowired
    private AccountPluginRepository accountPluginRepository;

    private AccountPlugin persistActivation(UUID accountId) {
        return accountPluginRepository.save(
                AccountPlugin.activate(accountId, PLUGIN_ID, Map.of("tenantId", "tenant-031")));
    }

    /**
     * Key generation writes plugin_data in its own transaction, so the instance held here goes
     * stale — saving it again would merge the pre-generation plugin_data back over the hash.
     */
    private AccountPlugin reload(Long id) {
        return accountPluginRepository.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("Generated key should validate through the indexed lookup")
    void generatedKeyShouldValidate() {
        AccountPlugin activation = persistActivation(TEST_ACCOUNT_ID);

        PluginApiKey key = pluginApiKeyService.generateApiKey(activation.getId());

        assertThat(accountPluginRepository.findById(activation.getId()))
                .get()
                .extracting(AccountPlugin::getApiKeyLookup)
                .isEqualTo(PluginApiKey.lookupOf(key.value()));
        assertThat(pluginApiKeyService.validateApiKey(key.value()))
                .get()
                .extracting(AccountPlugin::getId)
                .isEqualTo(activation.getId());
    }

    @Test
    @DisplayName("Point query should resolve the right activation among several")
    void shouldResolveCorrectActivationAmongSeveral() {
        AccountPlugin first = persistActivation(TEST_ACCOUNT_ID);
        AccountPlugin second = persistActivation(SECOND_ACCOUNT_ID);

        PluginApiKey firstKey = pluginApiKeyService.generateApiKey(first.getId());
        PluginApiKey secondKey = pluginApiKeyService.generateApiKey(second.getId());

        assertThat(pluginApiKeyService.validateApiKey(firstKey.value()))
                .get().extracting(AccountPlugin::getId).isEqualTo(first.getId());
        assertThat(pluginApiKeyService.validateApiKey(secondKey.value()))
                .get().extracting(AccountPlugin::getId).isEqualTo(second.getId());
    }

    @Test
    @DisplayName("Unknown and malformed keys should be rejected")
    void shouldRejectUnknownAndMalformedKeys() {
        AccountPlugin activation = persistActivation(TEST_ACCOUNT_ID);
        pluginApiKeyService.generateApiKey(activation.getId());

        assertThat(pluginApiKeyService.validateApiKey(PluginApiKey.generate().value())).isEmpty();
        assertThat(pluginApiKeyService.validateApiKey("not-a-key")).isEmpty();
    }

    @Test
    @DisplayName("Deactivated activation should not authenticate")
    void deactivatedActivationShouldNotAuthenticate() {
        AccountPlugin activation = persistActivation(TEST_ACCOUNT_ID);
        PluginApiKey key = pluginApiKeyService.generateApiKey(activation.getId());

        AccountPlugin stored = reload(activation.getId());
        stored.deactivate();
        accountPluginRepository.save(stored);

        assertThat(pluginApiKeyService.validateApiKey(key.value())).isEmpty();
    }

    @Test
    @DisplayName("Pre-V42 activation without a lookup should still authenticate")
    void legacyActivationShouldStillAuthenticate() {
        AccountPlugin activation = persistActivation(TEST_ACCOUNT_ID);
        PluginApiKey key = pluginApiKeyService.generateApiKey(activation.getId());

        // Simulate a row issued before V42: hash present, lookup never written.
        AccountPlugin stored = reload(activation.getId());
        stored.updateApiKeyLookup(null);
        accountPluginRepository.save(stored);

        assertThat(pluginApiKeyService.validateApiKey(key.value()))
                .get()
                .extracting(AccountPlugin::getId)
                .isEqualTo(activation.getId());
    }

    @Test
    @DisplayName("Rotation should move a legacy activation onto the indexed path")
    void rotationShouldPopulateLookup() {
        AccountPlugin activation = persistActivation(TEST_ACCOUNT_ID);
        PluginApiKey legacyKey = pluginApiKeyService.generateApiKey(activation.getId());
        AccountPlugin stored = reload(activation.getId());
        stored.updateApiKeyLookup(null);
        accountPluginRepository.save(stored);

        PluginApiKey rotated = pluginApiKeyService.rotateApiKey(TEST_ACCOUNT_ID);

        assertThat(pluginApiKeyService.validateApiKey(legacyKey.value()))
                .as("the pre-rotation key must stop working")
                .isEmpty();

        assertThat(accountPluginRepository
                .findActiveByPluginIdAndApiKeyLookup(PLUGIN_ID, PluginApiKey.lookupOf(rotated.value())))
                .get()
                .extracting(AccountPlugin::getId)
                .isEqualTo(activation.getId());
        assertThat(accountPluginRepository.findActiveByPluginIdWithoutApiKeyLookup(PLUGIN_ID))
                .extracting(AccountPlugin::getId)
                .doesNotContain(activation.getId());
    }
}

package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.Plugin;
import com.bitbi.dfm.plugin.domain.PluginEvent;
import com.bitbi.dfm.plugin.domain.PluginEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Bit BI plugin implementation.
 *
 * <p>This plugin enables integration between Data Forge Middleware and Bit BI,
 * allowing users to connect their DFM account to Bit BI via OAuth flow and
 * receive batch completion notifications.</p>
 *
 * <p>Plugin Data Schema (validated via JSON Schema):
 * <pre>
 * {
 *   "tenantId": "string (1-64 chars, alphanumeric with hyphens/underscores)",
 *   "apiKey": "string (optional, system-generated, format: plk_[a-zA-Z0-9]{32})"
 * }
 * </pre>
 *
 * <p>Supported Events:
 * <ul>
 *   <li>BATCH_COMPLETED - Notifies Bit BI when a batch upload completes</li>
 * </ul>
 *
 * @see Plugin
 * @see PluginEventType#BATCH_COMPLETED
 */
@Component
@ConditionalOnProperty(name = "plugins.bitbi.enabled", havingValue = "true", matchIfMissing = true)
public class BitBiPlugin implements Plugin {

    private static final Logger log = LoggerFactory.getLogger(BitBiPlugin.class);

    public static final String PLUGIN_ID = "bit-bi";
    public static final String PLUGIN_NAME = "Bit BI";
    public static final String PLUGIN_VERSION = "1.0.0";

    private SqlGenerationService sqlGenerationService;
    private PluginApiKeyService pluginApiKeyService;

    /**
     * Inject SqlGenerationService lazily to avoid circular dependency.
     */
    @Autowired
    @Lazy
    public void setSqlGenerationService(SqlGenerationService sqlGenerationService) {
        this.sqlGenerationService = sqlGenerationService;
    }

    /**
     * Inject PluginApiKeyService lazily to avoid circular dependency.
     */
    @Autowired
    @Lazy
    public void setPluginApiKeyService(PluginApiKeyService pluginApiKeyService) {
        this.pluginApiKeyService = pluginApiKeyService;
    }

    /**
     * JSON Schema for validating Bit BI plugin data.
     * Requires tenantId field with specific format constraints.
     * apiKey is optional and system-generated on activation.
     */
    private static final String SCHEMA_JSON = """
        {
          "$schema": "http://json-schema.org/draft-07/schema#",
          "type": "object",
          "required": ["tenantId"],
          "properties": {
            "tenantId": {
              "type": "string",
              "minLength": 1,
              "maxLength": 64,
              "pattern": "^[a-zA-Z0-9-_]+$",
              "description": "Bit BI tenant identifier"
            },
            "apiKey": {
              "type": "string",
              "minLength": 36,
              "maxLength": 36,
              "pattern": "^plk_[a-zA-Z0-9]{32}$",
              "description": "Plugin API Key (system-generated on activation)"
            }
          },
          "additionalProperties": false
        }
        """;

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    @Override
    public String getName() {
        return PLUGIN_NAME;
    }

    @Override
    public String getVersion() {
        return PLUGIN_VERSION;
    }

    @Override
    public Set<PluginEventType> getSupportedEvents() {
        return Set.of(PluginEventType.BATCH_COMPLETED);
    }

    @Override
    public String getSchemaJson() {
        return SCHEMA_JSON;
    }

    /**
     * Executes the plugin logic when a batch completion event is received.
     *
     * <p>For Bit BI, this generates SQL files from CSV diffs between batches
     * and stores them in S3 for later retrieval via the Plugin API.</p>
     *
     * @param event the batch completion event
     * @param accountPlugin the activation record containing tenantId
     */
    @Override
    public void execute(PluginEvent event, AccountPlugin accountPlugin) {
        String tenantId = (String) accountPlugin.getPluginData().get("tenantId");

        log.info("BitBiPlugin executing for tenant {} - event: {} resource: {}",
            tenantId,
            event.type().getEventName(),
            event.resourceId());

        if (event.type() == PluginEventType.BATCH_COMPLETED) {
            UUID batchId = event.resourceId();

            log.info("Triggering SQL generation for batch {} (tenant: {})",
                batchId, tenantId);

            try {
                sqlGenerationService.generateSqlForBatch(batchId, accountPlugin.getId());
            } catch (Exception e) {
                log.error("SQL generation failed for batch {} (tenant: {}): {}",
                    batchId, tenantId, e.getMessage(), e);
                // Don't rethrow - SQL generation failure shouldn't fail the event processing
            }
        }
    }

    /**
     * Called when Bit BI plugin is activated for an account.
     *
     * <p>Generates and stores an API Key for Plugin API authentication.
     * The API Key is stored in plugin_data and can be retrieved via the admin API.</p>
     *
     * @param accountPlugin the activation record
     */
    @Override
    public void onActivate(AccountPlugin accountPlugin) {
        String tenantId = (String) accountPlugin.getPluginData().get("tenantId");
        log.info("BitBiPlugin activated for account {} with tenant {}",
            accountPlugin.getAccountId(),
            tenantId);

        // Generate API Key for Plugin API authentication
        try {
            var apiKey = pluginApiKeyService.generateApiKey(accountPlugin.getId());
            log.info("Generated API Key for account {}: {}", accountPlugin.getAccountId(), apiKey);
        } catch (Exception e) {
            log.error("Failed to generate API Key for account {}: {}",
                accountPlugin.getAccountId(), e.getMessage(), e);
            // Don't fail activation if API key generation fails
        }
    }

    /**
     * Called when Bit BI plugin is deactivated for an account.
     *
     * <p>Logs the deactivation for audit purposes. In the future, this could
     * notify Bit BI about the disconnection.</p>
     *
     * @param accountPlugin the deactivated record
     */
    @Override
    public void onDeactivate(AccountPlugin accountPlugin) {
        String tenantId = (String) accountPlugin.getPluginData().get("tenantId");
        log.info("BitBiPlugin deactivated for account {} with tenant {}",
            accountPlugin.getAccountId(),
            tenantId);

        // TODO: Optionally notify Bit BI about the disconnection
    }
}

package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.Plugin;
import com.bitbi.dfm.plugin.domain.PluginEvent;
import com.bitbi.dfm.plugin.domain.PluginEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;

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
 *   "tenantId": "string (1-64 chars, alphanumeric with hyphens/underscores)"
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

    /**
     * JSON Schema for validating Bit BI plugin data.
     * Requires tenantId field with specific format constraints.
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
     * <p>For Bit BI, this will notify the Bit BI service about the completed batch
     * using the tenantId from the activation record.</p>
     *
     * <p>Note: In v1, this is a no-op placeholder. Actual Bit BI API integration
     * will be implemented when Bit BI provides their webhook endpoint.</p>
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

        // TODO: Implement actual Bit BI webhook notification
        // This will be implemented when Bit BI provides their webhook endpoint
        // For now, just log the event for validation

        if (event.type() == PluginEventType.BATCH_COMPLETED) {
            log.debug("Batch {} completed for tenant {}. Files: {}, Size: {}",
                event.resourceId(),
                tenantId,
                event.metadata().get("uploadedFilesCount"),
                event.metadata().get("totalSize"));
        }
    }

    /**
     * Called when Bit BI plugin is activated for an account.
     *
     * <p>Logs the activation for audit purposes. In the future, this could
     * notify Bit BI about the new connection.</p>
     *
     * @param accountPlugin the activation record
     */
    @Override
    public void onActivate(AccountPlugin accountPlugin) {
        String tenantId = (String) accountPlugin.getPluginData().get("tenantId");
        log.info("BitBiPlugin activated for account {} with tenant {}",
            accountPlugin.getAccountId(),
            tenantId);

        // TODO: Optionally notify Bit BI about the new connection
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

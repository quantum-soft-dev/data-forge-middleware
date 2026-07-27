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
 * Parquet Export plugin (028): pull-based access to Delta v2 Parquet files over Basic Auth with
 * one-time download links.
 * <p>
 * Unlike Bit BI this plugin generates nothing on batch events — it only mints Basic Auth
 * credentials at activation (returned once through the activation response, in
 * {@code login:password} form) and serves listing/download requests through
 * {@code ParquetExportApiController}.
 * </p>
 */
@Component
@ConditionalOnProperty(name = "plugins.parquet-export.enabled", havingValue = "true", matchIfMissing = true)
public class ParquetExportPlugin implements Plugin {

    public static final String PLUGIN_ID = ParquetExportCredentialsService.PLUGIN_ID;

    private static final Logger log = LoggerFactory.getLogger(ParquetExportPlugin.class);

    private static final String SCHEMA_JSON = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "properties": {},
              "additionalProperties": false
            }
            """;

    private final ParquetExportCredentialsService credentialsService;

    public ParquetExportPlugin(ParquetExportCredentialsService credentialsService) {
        this.credentialsService = credentialsService;
    }

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    @Override
    public String getName() {
        return "Parquet Export";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public Set<PluginEventType> getSupportedEvents() {
        return Set.of();
    }

    @Override
    public String getSchemaJson() {
        return SCHEMA_JSON;
    }

    @Override
    public void execute(PluginEvent event, AccountPlugin accountPlugin) {
        // Pull-based plugin: nothing to do on events (and none are subscribed).
    }

    @Override
    public String onActivate(AccountPlugin accountPlugin) {
        return credentialsService.mintCredentials(accountPlugin)
                .map(credentials -> {
                    log.info("parquet-export activated for accountId={}, credentials issued",
                            accountPlugin.getAccountId());
                    return credentials.basicAuthValue();
                })
                .orElse(null);
    }

    @Override
    public void onDeactivate(AccountPlugin accountPlugin) {
        // Credentials stay in plugin_data (reactivation keeps the same login); Basic Auth and
        // unconsumed links are blocked by the is_active flag, not by data removal.
        log.info("parquet-export deactivated for accountId={}", accountPlugin.getAccountId());
    }
}

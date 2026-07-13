package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.Plugin;
import com.bitbi.dfm.plugin.domain.PluginEvent;
import com.bitbi.dfm.plugin.domain.PluginEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Optional;
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
    private BatchRepository batchRepository;
    private AccountPluginRepository accountPluginRepository;
    private com.bitbi.dfm.site.domain.SiteRepository siteRepository;
    private DeltaSqlSweepWorker deltaSqlSweepWorker;

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
     * Inject BatchRepository lazily to avoid circular dependency.
     */
    @Autowired
    @Lazy
    public void setBatchRepository(BatchRepository batchRepository) {
        this.batchRepository = batchRepository;
    }

    /**
     * Inject AccountPluginRepository lazily to avoid circular dependency.
     */
    @Autowired
    @Lazy
    public void setAccountPluginRepository(AccountPluginRepository accountPluginRepository) {
        this.accountPluginRepository = accountPluginRepository;
    }

    /**
     * Inject SiteRepository lazily to avoid circular dependency (026: V2 routing).
     */
    @Autowired
    @Lazy
    public void setSiteRepository(com.bitbi.dfm.site.domain.SiteRepository siteRepository) {
        this.siteRepository = siteRepository;
    }

    /**
     * Inject DeltaSqlSweepWorker lazily to avoid circular dependency (026: V2 routing).
     */
    @Autowired
    @Lazy
    public void setDeltaSqlSweepWorker(DeltaSqlSweepWorker deltaSqlSweepWorker) {
        this.deltaSqlSweepWorker = deltaSqlSweepWorker;
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

            // Delta v2 batches route through the durable queue (segment.plugin_sql_at, 026):
            // per-site head-of-line claiming keeps generations in seq order, and the sweep
            // retries anything a crash or failure leaves pending. Inline generation here
            // would let the async pool process batches out of order.
            if (isDeltaV2Batch(batchId)) {
                log.info("Delta v2 batch {} — waking delta SQL worker (tenant: {})", batchId, tenantId);
                deltaSqlSweepWorker.wake();
                return;
            }

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

    private boolean isDeltaV2Batch(UUID batchId) {
        try {
            return batchRepository.findById(batchId)
                    .flatMap(batch -> siteRepository.findById(batch.getSiteId()))
                    .map(com.bitbi.dfm.site.domain.Site::isDeltaV2)
                    .orElse(false);
        } catch (Exception e) {
            log.warn("Failed to resolve site for batch {} — treating as V1: {}", batchId, e.getMessage());
            return false;
        }
    }

    /**
     * Called when Bit BI plugin is activated for an account (FR-006, FR-019).
     *
     * <p>This method:
     * <ul>
     *   <li>Sets the baseline_batch_id to the latest completed batch (for CSV initialization)</li>
     *   <li>Generates and stores an API Key for Plugin API authentication</li>
     * </ul>
     *
     * <p>The baseline batch is used for CSV file initialization - SQL generation is skipped
     * for this batch. Clients should download CSV files via /sites/{siteId}/files endpoint.</p>
     *
     * @param accountPlugin the activation record
     * @return the raw API key (shown only once), or null if generation failed
     */
    @Override
    public String onActivate(AccountPlugin accountPlugin) {
        String tenantId = (String) accountPlugin.getPluginData().get("tenantId");
        log.info("BitBiPlugin activated for account {} with tenant {}",
            accountPlugin.getAccountId(),
            tenantId);

        // Set baseline batch ID for CSV initialization
        // This batch will be used for downloading CSV files, not SQL generation
        try {
            Optional<Batch> latestBatch = batchRepository.findLatestCompletedByAccountId(
                accountPlugin.getAccountId());

            if (latestBatch.isPresent()) {
                accountPlugin.setBaselineBatchId(latestBatch.get().getId());
                accountPluginRepository.save(accountPlugin);
                log.info("Set baseline batch {} for account {} (CSV initialization)",
                    latestBatch.get().getId(), accountPlugin.getAccountId());
            } else {
                log.info("No completed batches found for account {} - baseline will be set on first batch",
                    accountPlugin.getAccountId());
                // baseline_batch_id stays null - will be set when first batch completes
            }
        } catch (Exception e) {
            log.error("Failed to set baseline batch for account {}: {}",
                accountPlugin.getAccountId(), e.getMessage(), e);
            // Don't fail activation if baseline setting fails
        }

        // Generate API Key for Plugin API authentication
        try {
            var apiKey = pluginApiKeyService.generateApiKey(accountPlugin.getId());
            log.info("Generated API Key for account {}", accountPlugin.getAccountId());
            return apiKey.value();  // Return raw key for inclusion in response
        } catch (Exception e) {
            log.error("Failed to generate API Key for account {}: {}",
                accountPlugin.getAccountId(), e.getMessage(), e);
            return null;  // Don't fail activation if API key generation fails
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

    /**
     * Called after plugin activation to log initialization status.
     *
     * <p>IMPORTANT: SQL generation is NO LONGER triggered on activation.
     * For the baseline batch (set in onActivate), clients should download CSV files
     * directly via /sites/{siteId}/files endpoint instead of receiving SQL statements.</p>
     *
     * <p>SQL generation only happens for batches AFTER the baseline batch,
     * producing delta statements (INSERT/UPDATE/DELETE).</p>
     *
     * @param accountPlugin the activation record
     * @param isNewOrReactivation true if this is a new activation or reactivation
     */
    @Async("pluginExecutor")
    public void initializeSqlFromLatestBatch(AccountPlugin accountPlugin, boolean isNewOrReactivation) {
        if (!isNewOrReactivation) {
            log.debug("Skipping initialization log for config update on account {}",
                accountPlugin.getAccountId());
            return;
        }

        // Log initialization status - no SQL generation for baseline batch
        if (accountPlugin.hasBaselineBatch()) {
            log.info("Plugin initialized for account {} with baseline batch {}. " +
                    "Client should download CSV files via /sites/{{siteId}}/files endpoint.",
                accountPlugin.getAccountId(), accountPlugin.getBaselineBatchId());
        } else {
            log.info("Plugin initialized for account {} without baseline batch. " +
                    "First completed batch will become baseline (CSV files, no SQL generation).",
                accountPlugin.getAccountId());
        }
    }
}

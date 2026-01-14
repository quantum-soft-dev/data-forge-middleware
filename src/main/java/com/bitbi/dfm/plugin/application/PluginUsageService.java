package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for tracking plugin usage.
 *
 * <p>Extracted from PluginEventDispatcher to ensure @Transactional works correctly.
 * Spring AOP proxies require external calls for annotations to be applied.</p>
 *
 * <p>Implements FR-018: Updates last_used_at timestamp on successful event dispatch.</p>
 */
@Service
public class PluginUsageService {

    private static final Logger log = LoggerFactory.getLogger(PluginUsageService.class);

    private final AccountPluginRepository accountPluginRepository;

    public PluginUsageService(AccountPluginRepository accountPluginRepository) {
        this.accountPluginRepository = accountPluginRepository;
    }

    /**
     * Updates the last_used_at timestamp for the account-plugin activation.
     * Called after successful event dispatch (FR-018).
     *
     * <p>Uses atomic UPDATE query to avoid overwriting concurrent changes
     * to other fields (e.g., baseline_batch_id set by SqlGenerationService).</p>
     *
     * <p>This method is transactional and should be called from external classes
     * to ensure Spring AOP proxy applies the transaction boundary.</p>
     *
     * @param accountPlugin the activation to update
     */
    @Transactional
    public void updateLastUsedAt(AccountPlugin accountPlugin) {
        try {
            // Use atomic UPDATE query instead of save() to avoid overwriting
            // concurrent changes to other fields (e.g., baseline_batch_id)
            accountPluginRepository.updateLastUsedAtById(accountPlugin.getId());
            log.debug("Updated last_used_at for plugin {} / account {}",
                    accountPlugin.getPluginId(), accountPlugin.getAccountId());
        } catch (Exception e) {
            // Don't fail the dispatch if we can't update the timestamp
            log.warn("Failed to update last_used_at for plugin {} / account {}: {}",
                    accountPlugin.getPluginId(), accountPlugin.getAccountId(), e.getMessage());
        }
    }
}

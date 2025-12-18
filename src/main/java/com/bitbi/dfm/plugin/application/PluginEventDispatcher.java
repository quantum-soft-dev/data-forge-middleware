package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.*;

/**
 * Service responsible for dispatching plugin events to subscribed plugins.
 *
 * <p>Implements FR-008 requirements:</p>
 * <ul>
 *   <li>Plugin execution has 30-second timeout</li>
 *   <li>Plugin failures are isolated (don't affect other plugins)</li>
 *   <li>Events are dispatched asynchronously</li>
 * </ul>
 *
 * <p>Implements FR-018: Updates last_used_at timestamp on successful event dispatch.</p>
 *
 * <p>User Story 5 (Phase 7) - Receive Batch Completion Notifications</p>
 */
@Service
public class PluginEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PluginEventDispatcher.class);
    private static final int PLUGIN_TIMEOUT_SECONDS = 30;

    private final PluginRegistry pluginRegistry;
    private final AccountPluginRepository accountPluginRepository;
    private final Executor pluginExecutor;

    public PluginEventDispatcher(
            PluginRegistry pluginRegistry,
            AccountPluginRepository accountPluginRepository,
            Executor pluginExecutor) {
        this.pluginRegistry = pluginRegistry;
        this.accountPluginRepository = accountPluginRepository;
        this.pluginExecutor = pluginExecutor;
    }

    /**
     * Dispatches an event to all subscribed plugins for the given account.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Finds all active plugins that support this event type</li>
     *   <li>For each plugin, checks if the account has an active activation</li>
     *   <li>Executes the plugin with 30-second timeout</li>
     *   <li>Updates last_used_at on success</li>
     *   <li>Logs errors but continues with other plugins</li>
     * </ol>
     *
     * @param event the plugin event to dispatch
     */
    @Async("pluginExecutor")
    public void dispatch(PluginEvent event) {
        if (event == null) {
            log.warn("Attempted to dispatch null event");
            return;
        }

        log.info("Dispatching plugin event: type={}, accountId={}, resourceId={}",
                event.type(), event.accountId(), event.resourceId());

        // Find all plugins that support this event type
        List<Plugin> subscribedPlugins = pluginRegistry.findBySupportedEvent(event.type());

        if (subscribedPlugins.isEmpty()) {
            log.debug("No plugins subscribed to event type: {}", event.type());
            return;
        }

        log.debug("Found {} plugins subscribed to {}", subscribedPlugins.size(), event.type());

        // Dispatch to each plugin that has an active activation for this account
        for (Plugin plugin : subscribedPlugins) {
            dispatchToPlugin(event, plugin);
        }
    }

    /**
     * Dispatches an event to a specific plugin if the account has an active activation.
     *
     * @param event the event to dispatch
     * @param plugin the target plugin
     */
    private void dispatchToPlugin(PluginEvent event, Plugin plugin) {
        String pluginId = plugin.getId();

        // Check if account has an active activation for this plugin
        AccountPlugin accountPlugin = accountPluginRepository
                .findByAccountIdAndPluginId(event.accountId(), pluginId)
                .orElse(null);

        if (accountPlugin == null || !accountPlugin.isActive()) {
            log.debug("Skipping plugin {} - no active activation for account {}",
                    pluginId, event.accountId());
            return;
        }

        // Execute plugin with timeout (FR-008)
        executeWithTimeout(event, plugin, accountPlugin);
    }

    /**
     * Executes a plugin with a 30-second timeout.
     * Failures are isolated and logged (FR-008).
     *
     * @param event the event to process
     * @param plugin the plugin to execute
     * @param accountPlugin the account's plugin activation
     */
    private void executeWithTimeout(PluginEvent event, Plugin plugin, AccountPlugin accountPlugin) {
        String pluginId = plugin.getId();
        long startTime = System.currentTimeMillis();

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                log.debug("Executing plugin {}: eventType={}, accountId={}",
                        pluginId, event.type(), event.accountId());
                plugin.execute(event, accountPlugin);
            } catch (Exception e) {
                log.error("Plugin {} execution failed: {}", pluginId, e.getMessage(), e);
                throw new PluginExecutionException(pluginId, e);
            }
        }, pluginExecutor);

        try {
            // Wait for plugin execution with timeout (FR-008)
            future.get(PLUGIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            long duration = System.currentTimeMillis() - startTime;
            log.info("Plugin {} executed successfully in {}ms for event {}",
                    pluginId, duration, event.type());

            // Update last_used_at on successful dispatch (FR-018)
            updateLastUsedAt(accountPlugin);

        } catch (TimeoutException e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Plugin {} timed out after {}ms (limit: {}s) for event {}",
                    pluginId, duration, PLUGIN_TIMEOUT_SECONDS, event.type());
            future.cancel(true); // Attempt to cancel the task

        } catch (ExecutionException e) {
            long duration = System.currentTimeMillis() - startTime;
            Throwable cause = e.getCause();
            log.error("Plugin {} failed after {}ms: {} - {}",
                    pluginId, duration, cause.getClass().getSimpleName(), cause.getMessage());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Plugin {} execution interrupted for event {}", pluginId, event.type());
        }
    }

    /**
     * Updates the last_used_at timestamp for the account-plugin activation.
     * Called after successful event dispatch (FR-018).
     *
     * @param accountPlugin the activation to update
     */
    @Transactional
    protected void updateLastUsedAt(AccountPlugin accountPlugin) {
        try {
            accountPlugin.recordUsage();
            accountPluginRepository.save(accountPlugin);
            log.debug("Updated last_used_at for plugin {} / account {}",
                    accountPlugin.getPluginId(), accountPlugin.getAccountId());
        } catch (Exception e) {
            // Don't fail the dispatch if we can't update the timestamp
            log.warn("Failed to update last_used_at for plugin {} / account {}: {}",
                    accountPlugin.getPluginId(), accountPlugin.getAccountId(), e.getMessage());
        }
    }

    /**
     * Exception thrown when plugin execution fails.
     */
    public static class PluginExecutionException extends RuntimeException {
        private final String pluginId;

        public PluginExecutionException(String pluginId, Throwable cause) {
            super("Plugin execution failed: " + pluginId, cause);
            this.pluginId = pluginId;
        }

        public String getPluginId() {
            return pluginId;
        }
    }
}

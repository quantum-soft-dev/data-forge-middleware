package com.bitbi.dfm.plugin.domain.exception;

import java.util.UUID;

/**
 * Exception thrown when attempting to deactivate a plugin that is not active.
 * Results in HTTP 403 Forbidden.
 *
 * <p>This can occur when:
 * <ul>
 *   <li>The plugin was never activated for this account</li>
 *   <li>The plugin was already deactivated</li>
 * </ul>
 */
public class PluginNotActivatedException extends RuntimeException {

    private final String pluginId;
    private final UUID accountId;

    public PluginNotActivatedException(String pluginId, UUID accountId) {
        super("Plugin '" + pluginId + "' is not activated for account " + accountId);
        this.pluginId = pluginId;
        this.accountId = accountId;
    }

    public PluginNotActivatedException(String pluginId, UUID accountId, String message) {
        super(message);
        this.pluginId = pluginId;
        this.accountId = accountId;
    }

    public String getPluginId() {
        return pluginId;
    }

    public UUID getAccountId() {
        return accountId;
    }
}

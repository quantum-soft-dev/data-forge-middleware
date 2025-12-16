package com.bitbi.dfm.plugin.domain;

import java.util.Set;

/**
 * Interface for compile-time registered plugins.
 * Plugins implement this interface to receive events and lifecycle callbacks.
 *
 * <p>Plugins are discovered at application startup via Spring component scanning
 * and registered in the PluginRegistry.</p>
 *
 * <p>Per FR-008, plugin.execute() calls have a 30-second timeout and failures
 * are isolated from other plugins and the core system.</p>
 */
public interface Plugin {

    /**
     * Returns the unique identifier for this plugin.
     * Must match the plugin_id in the plugin_configs table.
     * @return plugin ID (e.g., "bit-bi")
     */
    String getId();

    /**
     * Returns the human-readable name of this plugin.
     * @return display name (e.g., "Bit BI")
     */
    String getName();

    /**
     * Returns the version of this plugin.
     * @return version string (e.g., "1.0.0")
     */
    String getVersion();

    /**
     * Returns the set of event types this plugin wants to receive.
     * Only events of these types will be dispatched to this plugin.
     * @return set of supported event types
     */
    Set<PluginEventType> getSupportedEvents();

    /**
     * Returns the JSON Schema (draft-07) for validating plugin-specific data.
     * The schema is used to validate pluginData in activation requests.
     * @return JSON Schema as a string
     */
    String getSchemaJson();

    /**
     * Executes the plugin logic for a received event.
     * Called asynchronously with a 30-second timeout per FR-008.
     * Exceptions are caught and logged but do not propagate to other plugins.
     *
     * @param event the event to process
     * @param accountPlugin the activation record for this account-plugin pair
     */
    void execute(PluginEvent event, AccountPlugin accountPlugin);

    /**
     * Called when the plugin is activated for an account (FR-006).
     * Use for initialization tasks specific to this account-plugin activation.
     *
     * @param accountPlugin the newly created or reactivated activation record
     */
    void onActivate(AccountPlugin accountPlugin);

    /**
     * Called when the plugin is deactivated for an account (FR-006).
     * Use for cleanup tasks specific to this account-plugin activation.
     *
     * @param accountPlugin the deactivated activation record
     */
    void onDeactivate(AccountPlugin accountPlugin);
}

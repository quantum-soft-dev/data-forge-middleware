package com.bitbi.dfm.plugin.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the plugin system.
 *
 * <p>Configurable via application.yml:</p>
 * <pre>
 * plugin:
 *   execution:
 *     timeout-seconds: 30
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "plugin.execution")
public class PluginProperties {

    /**
     * Plugin execution timeout in seconds.
     * Default: 30 seconds (per FR-008 requirements).
     */
    private int timeoutSeconds = 30;

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}

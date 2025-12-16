package com.bitbi.dfm.plugin.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Entity representing plugin configuration stored in the database.
 * Links compile-time plugins to their Auth0 client credentials and runtime settings.
 *
 * <p>Business Rules:
 * <ul>
 *   <li>plugin_id must match a registered Plugin bean at startup</li>
 *   <li>is_enabled = false prevents new activations but doesn't deactivate existing ones</li>
 *   <li>client_id is used to verify API calls come from authorized OAuth clients</li>
 * </ul>
 */
@Entity
@Table(name = "plugin_configs")
@Getter
@NoArgsConstructor
public class PluginConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plugin_id", length = 64, nullable = false, unique = true)
    private String pluginId;

    @Column(name = "client_id", length = 64, nullable = false, unique = true)
    private String clientId;

    @Column(name = "display_name", length = 100, nullable = false)
    private String displayName;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled = true;

    @Type(JsonBinaryType.class)
    @Column(name = "config", columnDefinition = "jsonb")
    private Map<String, Object> config;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Factory method to create a new PluginConfig.
     */
    public static PluginConfig create(String pluginId, String clientId, String displayName) {
        PluginConfig config = new PluginConfig();
        config.pluginId = pluginId;
        config.clientId = clientId;
        config.displayName = displayName;
        config.enabled = true;
        config.config = new HashMap<>();
        config.createdAt = Instant.now();
        config.updatedAt = Instant.now();
        return config;
    }

    /**
     * Enables this plugin configuration.
     * Enabled plugins can accept new activations.
     */
    public void enable() {
        this.enabled = true;
        this.updatedAt = Instant.now();
    }

    /**
     * Disables this plugin configuration.
     * Disabled plugins cannot accept new activations but existing ones remain active.
     */
    public void disable() {
        this.enabled = false;
        this.updatedAt = Instant.now();
    }

    /**
     * Updates the plugin-specific configuration.
     * @param config new configuration map
     */
    public void updateConfig(Map<String, Object> config) {
        this.config = config != null ? new HashMap<>(config) : new HashMap<>();
        this.updatedAt = Instant.now();
    }

    /**
     * Updates the display name.
     * @param displayName new display name
     */
    public void updateDisplayName(String displayName) {
        this.displayName = displayName;
        this.updatedAt = Instant.now();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}

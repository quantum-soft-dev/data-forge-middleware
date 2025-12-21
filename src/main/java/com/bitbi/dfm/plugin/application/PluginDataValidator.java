package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.Plugin;
import com.bitbi.dfm.plugin.domain.PluginRegistry;
import com.bitbi.dfm.plugin.domain.exception.PluginDataValidationException;
import com.bitbi.dfm.plugin.domain.exception.PluginNotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for validating plugin-specific data against JSON Schema.
 *
 * <p>Per FR-004, pluginData must validate against the plugin's JSON Schema before activation.
 * Schemas are cached in memory after first load for performance.</p>
 */
@Service
public class PluginDataValidator {

    private static final Logger logger = LoggerFactory.getLogger(PluginDataValidator.class);

    private final PluginRegistry pluginRegistry;
    private final ObjectMapper objectMapper;
    private final JsonSchemaFactory schemaFactory;
    private final Map<String, JsonSchema> schemaCache;

    public PluginDataValidator(PluginRegistry pluginRegistry, ObjectMapper objectMapper) {
        this.pluginRegistry = pluginRegistry;
        this.objectMapper = objectMapper;
        this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        this.schemaCache = new ConcurrentHashMap<>();
    }

    /**
     * Validates plugin data against the plugin's JSON Schema.
     *
     * @param pluginId the plugin identifier
     * @param pluginData the data to validate
     * @throws PluginNotFoundException if plugin is not registered
     * @throws PluginDataValidationException if validation fails
     */
    public void validate(String pluginId, Map<String, Object> pluginData) {
        Plugin plugin = pluginRegistry.findById(pluginId)
            .orElseThrow(() -> new PluginNotFoundException(pluginId));

        JsonSchema schema = getOrLoadSchema(pluginId, plugin.getSchemaJson());

        try {
            JsonNode dataNode = objectMapper.valueToTree(pluginData);
            Set<ValidationMessage> errors = schema.validate(dataNode);

            if (!errors.isEmpty()) {
                List<String> errorMessages = errors.stream()
                    .map(ValidationMessage::getMessage)
                    .toList();

                logger.warn("Plugin data validation failed for {}: {}", pluginId, errorMessages);
                throw new PluginDataValidationException(pluginId, errorMessages);
            }

            logger.debug("Plugin data validation passed for {}", pluginId);

        } catch (PluginDataValidationException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Unexpected error during plugin data validation for {}: {}", pluginId, e.getMessage());
            throw new PluginDataValidationException(pluginId, "Invalid plugin data format: " + e.getMessage());
        }
    }

    /**
     * Gets a cached schema or loads and caches it.
     *
     * @param pluginId the plugin identifier (cache key)
     * @param schemaJson the JSON Schema string
     * @return the compiled JsonSchema
     */
    private JsonSchema getOrLoadSchema(String pluginId, String schemaJson) {
        return schemaCache.computeIfAbsent(pluginId, id -> {
            logger.debug("Loading JSON Schema for plugin: {}", id);
            try {
                JsonNode schemaNode = objectMapper.readTree(schemaJson);
                return schemaFactory.getSchema(schemaNode);
            } catch (JsonProcessingException e) {
                logger.error("Failed to parse JSON Schema for plugin {}: {}", id, e.getMessage());
                throw new IllegalStateException("Invalid JSON Schema for plugin: " + id, e);
            }
        });
    }

    /**
     * Clears the schema cache. Useful for testing or when plugins are reloaded.
     */
    public void clearCache() {
        schemaCache.clear();
        logger.info("Plugin schema cache cleared");
    }

    /**
     * Returns the number of cached schemas.
     * @return cache size
     */
    public int getCacheSize() {
        return schemaCache.size();
    }
}

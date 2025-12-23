package com.bitbi.dfm.plugin.domain;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * Value object representing a Plugin API Key for Bit BI Plugin API authentication.
 * <p>
 * Format: "plk_" prefix + 32 alphanumeric characters = 36 characters total
 * Example: "plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6"
 */
public record PluginApiKey(String value) {
    private static final String PREFIX = "plk_";
    private static final int KEY_LENGTH = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final String PATTERN = "^plk_[a-zA-Z0-9]{32}$";

    /**
     * Validates the API key format on construction.
     */
    public PluginApiKey {
        Objects.requireNonNull(value, "API key value cannot be null");
        if (!value.matches(PATTERN)) {
            throw new IllegalArgumentException("Invalid API key format. Expected: plk_ + 32 alphanumeric characters");
        }
    }

    /**
     * Generates a new random Plugin API Key.
     */
    public static PluginApiKey generate() {
        StringBuilder key = new StringBuilder(PREFIX);
        for (int i = 0; i < KEY_LENGTH; i++) {
            key.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return new PluginApiKey(key.toString());
    }

    /**
     * Creates a PluginApiKey from an existing value string.
     * Validates format before returning.
     *
     * @param value The API key string
     * @return PluginApiKey instance
     * @throws IllegalArgumentException if format is invalid
     */
    public static PluginApiKey of(String value) {
        return new PluginApiKey(value);
    }

    /**
     * Checks if a string is a valid Plugin API Key format.
     *
     * @param value The string to check
     * @return true if valid format
     */
    public static boolean isValid(String value) {
        return value != null && value.matches(PATTERN);
    }

    /**
     * Returns masked representation for logging (shows only last 4 characters).
     */
    @Override
    public String toString() {
        return PREFIX + "****" + value.substring(value.length() - 4);
    }

    /**
     * Returns the full API key value (use with caution - avoid logging).
     */
    public String rawValue() {
        return value;
    }
}

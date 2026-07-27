package com.bitbi.dfm.plugin.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
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
     * Computes the indexed lookup handle for a raw API key: the lowercase hex SHA-256 digest (031).
     * <p>
     * Stored in {@code account_plugins.api_key_lookup} (V42) so that validation can resolve the
     * activation with one indexed point query instead of scanning every activation. This digest is
     * <strong>not</strong> the verification secret — the BCrypt hash remains the source of truth.
     * An unsalted digest is adequate as an index key because the key carries ~190 bits of
     * {@link SecureRandom} entropy, which puts precomputation and rainbow tables out of reach.
     * </p>
     *
     * @param rawValue the raw API key
     * @return 64-character lowercase hex SHA-256 digest
     */
    public static String lookupOf(String rawValue) {
        Objects.requireNonNull(rawValue, "API key value cannot be null");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawValue.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS platform requirements; unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Returns the indexed lookup handle for this key.
     *
     * @see #lookupOf(String)
     */
    public String lookup() {
        return lookupOf(value);
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

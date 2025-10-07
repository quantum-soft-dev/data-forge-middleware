package com.bitbi.dfm.upload.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Value object representing file checksum for integrity verification.
 * <p>
 * Uses MD5 algorithm for checksum calculation.
 * Immutable and validated at construction time.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public record FileChecksum(String algorithm, String value) {

    private static final String DEFAULT_ALGORITHM = "MD5";

    /**
     * Constructs FileChecksum with validation.
     *
     * @param algorithm checksum algorithm (e.g., "MD5", "SHA-256")
     * @param value     checksum value in hexadecimal format
     * @throws IllegalArgumentException if algorithm or value is null/empty
     */
    public FileChecksum {
        Objects.requireNonNull(algorithm, "Algorithm cannot be null");
        Objects.requireNonNull(value, "Checksum value cannot be null");

        if (algorithm.isBlank()) {
            throw new IllegalArgumentException("Algorithm cannot be blank");
        }

        if (value.isBlank()) {
            throw new IllegalArgumentException("Checksum value cannot be blank");
        }

        // Validate hexadecimal format
        if (!value.matches("^[0-9a-fA-F]+$")) {
            throw new IllegalArgumentException("Checksum value must be in hexadecimal format");
        }
    }

    /**
     * Calculate MD5 checksum for given data.
     *
     * @param data byte array to calculate checksum for
     * @return FileChecksum with MD5 algorithm and calculated value
     * @throws RuntimeException if MD5 algorithm is not available
     */
    public static FileChecksum calculateMD5(byte[] data) {
        Objects.requireNonNull(data, "Data cannot be null");

        try {
            MessageDigest md = MessageDigest.getInstance(DEFAULT_ALGORITHM);
            byte[] digest = md.digest(data);
            String hexValue = bytesToHex(digest);
            return new FileChecksum(DEFAULT_ALGORITHM, hexValue);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    /**
     * Verify checksum matches the provided data.
     *
     * @param data byte array to verify
     * @return true if checksum matches
     */
    public boolean verify(byte[] data) {
        FileChecksum calculated = calculateMD5(data);
        return this.value.equalsIgnoreCase(calculated.value);
    }

    /**
     * Convert byte array to hexadecimal string.
     *
     * @param bytes byte array
     * @return hexadecimal string
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    /**
     * Create FileChecksum from hexadecimal string (default MD5 algorithm).
     *
     * @param hexValue checksum value in hexadecimal format
     * @return FileChecksum with MD5 algorithm
     */
    public static FileChecksum fromHex(String hexValue) {
        return new FileChecksum(DEFAULT_ALGORITHM, hexValue);
    }
}

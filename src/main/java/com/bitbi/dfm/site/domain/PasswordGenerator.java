package com.bitbi.dfm.site.domain;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;

/**
 * Domain service for generating secure random passwords for site authentication.
 * <p>
 * Generates alphanumeric passwords (lowercase, uppercase, and digits) with length between 8-12 characters.
 * Uses SecureRandom for cryptographically strong randomness.
 * <p>
 * Feature: 007-adding-a-site (US1, FR-017)
 */
@Service
public class PasswordGenerator {

    private static final String CHARACTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 12;
    private final SecureRandom secureRandom;

    public PasswordGenerator() {
        this.secureRandom = new SecureRandom();
    }

    /**
     * Generates a random password with length between 8-12 characters.
     *
     * @return A random alphanumeric password
     */
    public String generate() {
        int length = MIN_LENGTH + secureRandom.nextInt(MAX_LENGTH - MIN_LENGTH + 1);
        StringBuilder password = new StringBuilder(length);

        for (int i = 0; i < length; i++) {
            int randomIndex = secureRandom.nextInt(CHARACTERS.length());
            password.append(CHARACTERS.charAt(randomIndex));
        }

        return password.toString();
    }
}

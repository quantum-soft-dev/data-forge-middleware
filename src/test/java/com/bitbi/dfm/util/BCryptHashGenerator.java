package com.bitbi.dfm.util;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Utility to generate BCrypt hashes for test data.
 * Run this test to generate hashes for test secrets.
 */
public class BCryptHashGenerator {

    @Test
    void generateBCryptHashesForTestData() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        String[] secrets = {
            "admin-site-secret",
            "valid-secret-uuid",
            "inactive-secret-uuid",
            "batch-test-secret",
            "test-client-secret-uuid",
            "inactive-secret",
            "orphaned-secret"
        };

        System.out.println("\n-- BCrypt hashes for test secrets:");
        System.out.println("-- Generated on: " + java.time.LocalDateTime.now());
        System.out.println("-- Copy these hashes to test-data.sql\n");

        for (String secret : secrets) {
            String hash = encoder.encode(secret);
            System.out.println("-- Plaintext: " + secret);
            System.out.println("   Hash:      " + hash);

            // Verify it works
            boolean matches = encoder.matches(secret, hash);
            System.out.println("   Verified:  " + (matches ? "✓ OK" : "✗ FAILED"));
            System.out.println();
        }
    }
}

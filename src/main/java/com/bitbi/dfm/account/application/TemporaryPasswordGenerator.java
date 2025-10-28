package com.bitbi.dfm.account.application;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * TemporaryPasswordGenerator generates secure random passwords for new users.
 * <p>
 * Passwords meet the following requirements:
 * - 12 characters long
 * - Contains uppercase letters (A-Z)
 * - Contains lowercase letters (a-z)
 * - Contains digits (0-9)
 * - Contains special characters (!@#$%^&*)
 * - Characters are shuffled to avoid predictable patterns
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class TemporaryPasswordGenerator {

    private static final String UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWERCASE = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS = "0123456789";
    private static final String SPECIAL = "!@#$%^&*";
    private static final String ALL_CHARS = UPPERCASE + LOWERCASE + DIGITS + SPECIAL;

    private static final int PASSWORD_LENGTH = 12;
    private static final SecureRandom random = new SecureRandom();

    /**
     * Generate a secure random temporary password.
     * <p>
     * Guarantees at least one character from each character class
     * (uppercase, lowercase, digit, special) to meet common password policies.
     * </p>
     *
     * @return 12-character temporary password
     */
    public String generate() {
        List<Character> passwordChars = new ArrayList<>(PASSWORD_LENGTH);

        // Ensure at least one character from each class
        passwordChars.add(UPPERCASE.charAt(random.nextInt(UPPERCASE.length())));
        passwordChars.add(LOWERCASE.charAt(random.nextInt(LOWERCASE.length())));
        passwordChars.add(DIGITS.charAt(random.nextInt(DIGITS.length())));
        passwordChars.add(SPECIAL.charAt(random.nextInt(SPECIAL.length())));

        // Fill remaining characters randomly
        for (int i = 4; i < PASSWORD_LENGTH; i++) {
            passwordChars.add(ALL_CHARS.charAt(random.nextInt(ALL_CHARS.length())));
        }

        // Shuffle to avoid predictable patterns (e.g., special char always at end)
        Collections.shuffle(passwordChars, random);

        StringBuilder password = new StringBuilder(PASSWORD_LENGTH);
        for (Character c : passwordChars) {
            password.append(c);
        }

        return password.toString();
    }
}

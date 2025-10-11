package com.bitbi.dfm.account.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Phone number Value Object with validation.
 * <p>
 * Enforces E.164 international phone number format and maximum length constraints.
 * Immutable by design - any change requires creating a new instance.
 * </p>
 *
 * <h3>Validation Rules:</h3>
 * <ul>
 *   <li>Maximum length: 50 characters (database constraint)</li>
 *   <li>Format: E.164 standard (optional + prefix, 1-15 digits)</li>
 *   <li>Examples: +1234567890, +442071234567, 1234567890</li>
 *   <li>Null is allowed (optional field)</li>
 * </ul>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public final class Phone {

    /**
     * E.164 phone number pattern: optional +, digits, length 7-15.
     * More lenient than strict E.164 to accommodate various formats.
     * Allows leading zeros to support all valid international numbers.
     */
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "^\\+?[0-9]{7,15}$"
    );

    private static final int MAX_LENGTH = 50;

    private final String value;

    /**
     * Private constructor - use factory methods.
     */
    private Phone(String value) {
        this.value = value;
    }

    /**
     * Create Phone from string value with validation.
     *
     * @param value phone number string (can be null or blank)
     * @return Phone instance, or null if input is null/blank
     * @throws IllegalArgumentException if phone format is invalid or too long
     */
    public static Phone of(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String trimmed = value.trim();
        validate(trimmed);

        return new Phone(trimmed);
    }

    /**
     * Validate phone number format and length.
     *
     * @param phone phone number to validate
     * @throws IllegalArgumentException if validation fails
     */
    private static void validate(String phone) {
        if (phone.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("Phone number exceeds maximum length of %d characters: %d",
                            MAX_LENGTH, phone.length())
            );
        }

        if (!PHONE_PATTERN.matcher(phone).matches()) {
            throw new IllegalArgumentException(
                    "Invalid phone number format. Expected E.164 format (e.g., +1234567890): " + phone
            );
        }
    }

    /**
     * Get phone number value.
     *
     * @return phone number string
     */
    public String getValue() {
        return value;
    }

    /**
     * Check if phone is empty/null.
     *
     * @return true if phone is null
     */
    public boolean isEmpty() {
        return value == null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Phone phone)) return false;
        return Objects.equals(value, phone.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value != null ? value : "";
    }
}
